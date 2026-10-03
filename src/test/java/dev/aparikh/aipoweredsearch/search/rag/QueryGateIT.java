package dev.aparikh.aipoweredsearch.search.rag;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import dev.aparikh.aipoweredsearch.evaluation.CandidateRecorder;
import dev.aparikh.aipoweredsearch.evaluation.HashingEmbeddingModel;
import dev.aparikh.aipoweredsearch.evaluation.RagEvalData;
import dev.aparikh.aipoweredsearch.evaluation.RagEvalFixture;
import dev.aparikh.aipoweredsearch.evaluation.RagEvalTestConfiguration;
import dev.aparikh.aipoweredsearch.indexing.IndexService;
import dev.aparikh.aipoweredsearch.search.SearchService;
import dev.aparikh.aipoweredsearch.search.model.AskRequest;
import dev.aparikh.aipoweredsearch.search.model.AskResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.solr.SolrContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W6 on the real wiring with offline models: with the planner and the gate on, a first-turn title
 * lookup makes zero planner calls and returns exactly the documents of the planner-off baseline.
 */
@SpringBootTest(properties = {
        "solr.default.collection=" + QueryGateIT.COLLECTION,
        "spring.ai.openai.api-key=test-key",
        "search.rag.planner.enabled=true",
        "search.rag.gate.enabled=true"})
@Import({PostgresTestConfiguration.class, SolrTestConfiguration.class, RagEvalTestConfiguration.class,
        QueryGateIT.OfflineModels.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryGateIT {

    static final String COLLECTION = "rag-gate";

    @TestConfiguration(proxyBeanMethods = false)
    static class OfflineModels {

        static final AtomicInteger PLANNER_CALLS = new AtomicInteger();

        @Bean
        @Primary
        EmbeddingModel hashingEmbeddingModel() {
            return new HashingEmbeddingModel();
        }

        @Bean
        @Primary
        ChatModel stubChatModel() {
            return prompt -> {
                if (prompt.getSystemMessage().getText().contains("You are the query planner")) {
                    PLANNER_CALLS.incrementAndGet();
                    return reply("""
                            {"standalone": "Books by George R.R. Martin cheaper than A Game of Thrones",
                             "keywordQuery": "George R.R. Martin", "variants": ["a", "b"], "filters": []}
                            """);
                }
                return reply("stub answer");
            };
        }

        private static ChatResponse reply(String text) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
    }

    @Autowired
    private SolrContainer solr;

    @Autowired
    private SolrClient solrClient;

    @Autowired
    private IndexService indexService;

    @Autowired
    private SearchService searchService;

    @Autowired
    private CandidateRecorder candidateRecorder;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeAll
    void indexFixture() throws Exception {
        RagEvalFixture.uploadConfigSet(solr);
        RagEvalFixture.createCollection(solrClient, COLLECTION);
        RagEvalFixture.index(indexService, solrClient, COLLECTION, RagEvalData.fixture().books());
    }

    @Test
    void aGatedLookupMakesNoPlannerCallAndMatchesTheBaseline() throws Exception {
        int before = OfflineModels.PLANNER_CALLS.get();

        AskResponse response = searchService.ask(new AskRequest("A Clash of Kings", "gate-lookup"));

        assertThat(OfflineModels.PLANNER_CALLS.get()).isEqualTo(before);
        Map<String, List<String>> golden = golden("k-01");
        assertThat(candidateRecorder.lastCandidates("gate-lookup")).containsExactlyElementsOf(golden.get("candidates"));
        assertThat(response.sources()).containsExactlyElementsOf(golden.get("context"));
        assertThat(meterRegistry.get(QueryGate.METRIC).tag(QueryGate.OUTCOME_TAG, QueryGate.SKIPPED).counter().count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void aFollowUpIsStillPlanned() {
        searchService.ask(new AskRequest("Recommend an epic fantasy series with political intrigue.", "gate-follow-up"));
        int before = OfflineModels.PLANNER_CALLS.get();

        searchService.ask(new AskRequest("Anything cheaper by the same author?", "gate-follow-up"));

        assertThat(OfflineModels.PLANNER_CALLS.get()).isEqualTo(before + 1);
        assertThat(meterRegistry.get(QueryGate.METRIC).tag(QueryGate.OUTCOME_TAG, QueryGate.PLANNED).counter().count())
                .isGreaterThanOrEqualTo(1);
    }

    private Map<String, List<String>> golden(String caseId) throws Exception {
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/eval/golden-ask.json"))) {
            Map<String, Map<String, List<String>>> golden = JsonMapper.builder().build()
                    .readValue(in, new TypeReference<>() {
                    });
            return golden.get(caseId);
        }
    }
}
