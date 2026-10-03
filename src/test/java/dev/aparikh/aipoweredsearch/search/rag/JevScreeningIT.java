package dev.aparikh.aipoweredsearch.search.rag;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import dev.aparikh.aipoweredsearch.evaluation.HashingEmbeddingModel;
import dev.aparikh.aipoweredsearch.evaluation.RagEvalData;
import dev.aparikh.aipoweredsearch.evaluation.RagEvalFixture;
import dev.aparikh.aipoweredsearch.evaluation.RagEvalTestConfiguration;
import dev.aparikh.aipoweredsearch.indexing.IndexService;
import dev.aparikh.aipoweredsearch.search.SearchService;
import dev.aparikh.aipoweredsearch.search.model.AskRequest;
import dev.aparikh.aipoweredsearch.search.model.AskResponse;
import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.solr.SolrContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W4 end to end with the Jev passage filter switched on. Embeddings and the chat model are
 * offline; only Jev itself is real, and only where a key is required.
 */
class JevScreeningIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class OfflineModels {

        @Bean
        @Primary
        EmbeddingModel hashingEmbeddingModel() {
            return new HashingEmbeddingModel();
        }

        @Bean
        @Primary
        ChatModel stubChatModel() {
            return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("stub answer"))));
        }
    }

    abstract static class FixtureBase {

        @Autowired
        SolrContainer solr;

        @Autowired
        SolrClient solrClient;

        @Autowired
        IndexService indexService;

        abstract String collection();

        @BeforeAll
        void indexFixture() throws Exception {
            RagEvalFixture.uploadConfigSet(solr);
            RagEvalFixture.createCollection(solrClient, collection());
            RagEvalFixture.index(indexService, solrClient, collection(), RagEvalData.fixture().books());
        }
    }

    /**
     * Fail-open: with the TypeSafe API unreachable, {@code /ask} still answers, with exactly the
     * context it would have had without Jev. Needs no key.
     */
    @Nested
    @AutoConfigureTestRestTemplate
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "solr.default.collection=rag-jev-fail-open",
            "spring.ai.openai.api-key=test-key",
            "search.rag.jev.enabled=true",
            "search.rag.jev.timeout=3s",
            "spring.ai.typesafe.api-key=test-key",
            "spring.ai.typesafe.base-url=http://127.0.0.1:9",
            "spring.ai.typesafe.timeout=1s"})
    @Import({PostgresTestConfiguration.class, SolrTestConfiguration.class, RagEvalTestConfiguration.class,
            OfflineModels.class})
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FailOpen extends FixtureBase {

        @LocalServerPort
        int port;

        @Autowired
        TestRestTemplate restTemplate;

        @Autowired
        RagPostProcessors postProcessors;

        @Override
        String collection() {
            return "rag-jev-fail-open";
        }

        @Test
        void anUnreachableTypeSafeApiDegradesToTheRerankerAlone() throws Exception {
            assertThat(postProcessors.processors()).hasSize(2);

            ResponseEntity<AskResponse> response = restTemplate.postForEntity(
                    "http://localhost:" + port + "/api/v1/search/ask",
                    new AskRequest("A Clash of Kings", "jev-fail-open"), AskResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            // Jev passed the candidates through untouched, so the reranker produced the same
            // context as the pipeline without Jev (the golden record for this question).
            assertThat(response.getBody().sources()).containsExactlyElementsOf(goldenContext("k-01"));
        }

        private List<String> goldenContext(String caseId) throws Exception {
            try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/eval/golden-ask.json"))) {
                Map<String, Map<String, List<String>>> golden = JsonMapper.builder().build()
                        .readValue(in, new TypeReference<>() {
                        });
                return golden.get(caseId).get("context");
            }
        }
    }

    /**
     * Screening against the real TypeSafe API: seeded prompt-injection documents never reach
     * the prompt context. Skipped without {@code TYPESAFE_API_KEY}.
     */
    @Nested
    @EnabledIfEnvironmentVariable(named = "TYPESAFE_API_KEY", matches = ".+")
    @SpringBootTest(properties = {
            "solr.default.collection=rag-jev-live",
            "spring.ai.openai.api-key=test-key",
            "search.rag.jev.enabled=true"})
    @Import({PostgresTestConfiguration.class, SolrTestConfiguration.class, RagEvalTestConfiguration.class,
            OfflineModels.class})
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class LiveScreening extends FixtureBase {

        @Autowired
        SearchService searchService;

        @Override
        String collection() {
            return "rag-jev-live";
        }

        @Test
        void seededInjectionDocumentsNeverReachThePromptContext() {
            for (RagEvalData.EvalCase evalCase : RagEvalData.evalSet().cases()) {
                if (!"injection".equals(evalCase.category())) {
                    continue;
                }
                AskResponse response = searchService.ask(new AskRequest(evalCase.lastTurn(), "jev-" + evalCase.id()));
                assertThat(response.sources()).as(evalCase.id()).noneMatch(id -> id.startsWith("inj-"));
            }
        }
    }
}
