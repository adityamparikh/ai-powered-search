package dev.aparikh.aipoweredsearch.evaluation;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import dev.aparikh.aipoweredsearch.indexing.IndexService;
import dev.aparikh.aipoweredsearch.search.SearchService;
import dev.aparikh.aipoweredsearch.search.model.AskRequest;
import dev.aparikh.aipoweredsearch.search.model.AskResponse;
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
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden regression for {@code /ask} with every modular-RAG flag at its default (epic #32 ground
 * rule: "with all new flags off, /ask returns the same documents in the same order").
 *
 * <p>Replays every case of the evaluation set and compares, per case, the fused candidates handed
 * to the reranker and the documents placed in the prompt context against
 * {@code eval/golden-ask.json}. The golden file was recorded on the pre-W5 pipeline. No API keys
 * are needed: embeddings come from {@link HashingEmbeddingModel} and the chat model is a stub. The
 * stub's reply is not a valid ranking, so the reranker takes its documented fallback (keep
 * retrieval order, truncate to top-k), which makes the context deterministic too.</p>
 *
 * <p>To re-record after an <em>intentional</em> ordering change, run with
 * {@code -Drag.golden.record=true} and explain the diff in the PR.</p>
 */
@SpringBootTest(properties = {"solr.default.collection=" + RagGoldenRegressionIT.COLLECTION,
        "spring.ai.openai.api-key=test-key"})
@Import({PostgresTestConfiguration.class, SolrTestConfiguration.class, RagEvalTestConfiguration.class,
        RagGoldenRegressionIT.OfflineModels.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagGoldenRegressionIT {

    static final String COLLECTION = "rag-golden";
    static final String GOLDEN_RESOURCE = "/eval/golden-ask.json";
    static final Path GOLDEN_SOURCE = Path.of("src", "test", "resources", "eval", "golden-ask.json");

    /** What a case retrieved: fused candidates (pre-rerank) and the final prompt context. */
    record Outcome(List<String> candidates, List<String> context) {
    }

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

    @BeforeAll
    void indexFixture() throws Exception {
        RagEvalFixture.uploadConfigSet(solr);
        RagEvalFixture.createCollection(solrClient, COLLECTION);
        RagEvalFixture.index(indexService, solrClient, COLLECTION, RagEvalData.fixture().books());
    }

    @Test
    void askReturnsTheGoldenDocumentsInTheGoldenOrder() throws Exception {
        Map<String, Outcome> actual = new LinkedHashMap<>();
        for (RagEvalData.EvalCase evalCase : RagEvalData.evalSet().cases()) {
            actual.put(evalCase.id(), replay(evalCase));
        }

        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        if (Boolean.getBoolean("rag.golden.record")) {
            Files.writeString(GOLDEN_SOURCE, mapper.writeValueAsString(actual) + "\n");
            return;
        }

        Map<String, Outcome> golden;
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream(GOLDEN_RESOURCE))) {
            golden = mapper.readValue(in, new TypeReference<LinkedHashMap<String, Outcome>>() {
            });
        }
        assertThat(actual.keySet()).containsExactlyElementsOf(golden.keySet());
        actual.forEach((caseId, outcome) -> {
            assertThat(outcome.candidates()).as("fused candidates for %s", caseId)
                    .containsExactlyElementsOf(golden.get(caseId).candidates());
            assertThat(outcome.context()).as("prompt context for %s", caseId)
                    .containsExactlyElementsOf(golden.get(caseId).context());
        });
    }

    private Outcome replay(RagEvalData.EvalCase evalCase) {
        String conversationId = "golden-" + evalCase.id();
        for (String turn : evalCase.turns().subList(0, evalCase.turns().size() - 1)) {
            searchService.ask(new AskRequest(turn, conversationId));
        }
        AskResponse response = searchService.ask(new AskRequest(evalCase.lastTurn(), conversationId));
        return new Outcome(candidateRecorder.lastCandidates(conversationId), response.sources());
    }
}
