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
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.common.params.SolrParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.solr.SolrContainer;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * W1 end to end on the real {@code ragChatClient} wiring, with offline models (no API keys).
 *
 * <p>A stub chat model plays the planner and returns a fixed plan for the epic's running example.
 * Everything else, including Solr retrieval, RRF fusion, filter validation and the standalone
 * hand-off to the reranker, runs for real against the evaluation fixture. A spy on the
 * {@link SolrClient} records every Solr request, which is how the test proves that invalid
 * filters never reach Solr.</p>
 */
@SpringBootTest(properties = {
        "solr.default.collection=" + QueryPlannerIT.COLLECTION,
        "spring.ai.openai.api-key=test-key",
        "search.rag.planner.enabled=true",
        "search.rag.planner.filters.enabled=true",
        "search.rag.planner.variants=2"})
@Import({PostgresTestConfiguration.class, SolrTestConfiguration.class, RagEvalTestConfiguration.class,
        QueryPlannerIT.OfflinePlanner.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryPlannerIT {

    static final String COLLECTION = "rag-planner";

    static final String TURN_1 = "Recommend an epic fantasy series with political intrigue.";
    static final String TURN_2 = "Anything cheaper by the same author?";
    static final String STANDALONE = "Books by George R.R. Martin cheaper than A Game of Thrones";

    /** The plan for turn 2, including three filters the validator must drop. */
    static final String TURN_2_PLAN = """
            {"standalone": "Books by George R.R. Martin cheaper than A Game of Thrones",
             "keywordQuery": "George R.R. Martin",
             "variants": ["Lower-priced novels by the author of A Song of Ice and Fire",
                          "Cheaper George R.R. Martin paperbacks"],
             "hydePassage": "A sweeping saga of rival noble houses.",
             "filters": ["metadata_author:\\"George R.R. Martin\\"", "metadata_price:[* TO 9.98]",
                         "{!func}div(1,0)", "_query_:\\"{!dismax}martin\\"", "unknown_field:x"]}
            """;

    static final String TURN_1_PLAN = """
            {"standalone": "Recommend an epic fantasy series with political intrigue",
             "keywordQuery": "epic fantasy political intrigue",
             "variants": ["Fantasy sagas about scheming noble houses", "Court politics in an invented kingdom"],
             "hydePassage": "Rival houses scheme for a contested throne.",
             "filters": []}
            """;

    @TestConfiguration(proxyBeanMethods = false)
    static class OfflinePlanner {

        static final AtomicBoolean PLANNER_DOWN = new AtomicBoolean();
        static final AtomicInteger PLANNER_CALLS = new AtomicInteger();

        @Bean
        @Primary
        EmbeddingModel hashingEmbeddingModel() {
            return new HashingEmbeddingModel();
        }

        /** Plans when it sees the planner's system prompt; otherwise answers like the generator. */
        @Bean
        @Primary
        ChatModel stubChatModel() {
            return prompt -> {
                if (isPlannerPrompt(prompt)) {
                    PLANNER_CALLS.incrementAndGet();
                    if (PLANNER_DOWN.get()) {
                        throw new IllegalStateException("planner unavailable");
                    }
                    String latest = Objects.requireNonNullElse(prompt.getUserMessage().getText(), "");
                    return reply(latest.contains("Latest user message:\n" + TURN_2) ? TURN_2_PLAN : TURN_1_PLAN);
                }
                return reply("Try A Game of Thrones by George R.R. Martin ($9.99).");
            };
        }

        /** Spies on the application's SolrClient so tests can see every request Solr receives. */
        @Bean
        static BeanPostProcessor solrClientSpy() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof SolrClient && !Mockito.mockingDetails(bean).isSpy() ? Mockito.spy(bean) : bean;
                }
            };
        }

        private static boolean isPlannerPrompt(Prompt prompt) {
            return prompt.getSystemMessage().getText().contains("You are the query planner");
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

    @BeforeAll
    void indexFixture() throws Exception {
        RagEvalFixture.uploadConfigSet(solr);
        RagEvalFixture.createCollection(solrClient, COLLECTION);
        RagEvalFixture.index(indexService, solrClient, COLLECTION, RagEvalData.fixture().books());
    }

    @BeforeEach
    void plannerUp() {
        OfflinePlanner.PLANNER_DOWN.set(false);
        Mockito.clearInvocations(solrClient);
    }

    @Test
    void runningExampleRetrievesAndReranksForTheStandaloneQueryWithValidatedFilters() {
        String conversation = "planner-running-example";
        searchService.ask(new AskRequest(TURN_1, conversation));
        AskResponse response = searchService.ask(new AskRequest(TURN_2, conversation));

        CandidateRecorder.Capture capture = candidateRecorder.last(conversation).orElseThrow();
        // P1 + the post-processor bug: the reranker judges against the standalone rewrite.
        assertThat(capture.queryText()).isEqualTo(STANDALONE);
        // P2: author exact match and price < 9.99 leave exactly the three cheaper GRRM books.
        assertThat(capture.candidateIds()).containsExactlyInAnyOrder("grrm-02", "grrm-04", "grrm-07");
        assertThat(response.sources()).isNotEmpty().allMatch(id -> id.startsWith("grrm-"));
    }

    @Test
    void invalidFiltersNeverReachSolr() throws Exception {
        String conversation = "planner-filters";
        searchService.ask(new AskRequest(TURN_1, conversation));
        Mockito.clearInvocations(solrClient);

        searchService.ask(new AskRequest(TURN_2, conversation));

        ArgumentCaptor<SolrParams> params = ArgumentCaptor.forClass(SolrParams.class);
        verify(solrClient, atLeastOnce()).query(anyString(), params.capture(), any(SolrRequest.METHOD.class));
        List<String> filterQueries = params.getAllValues().stream()
                .map(p -> p.getParams("fq"))
                .filter(Objects::nonNull)
                .flatMap(Arrays::stream)
                .toList();
        assertThat(filterQueries).isNotEmpty()
                .allSatisfy(fq -> assertThat(fq)
                        .doesNotContain("{!")
                        .doesNotContain("_query_")
                        .doesNotContain("unknown_field")
                        .contains("metadata_author:\"George R.R. Martin\""));
    }

    @Test
    void aPlannerOutageFallsBackToTodaysRetrieval() {
        OfflinePlanner.PLANNER_DOWN.set(true);
        int callsBefore = OfflinePlanner.PLANNER_CALLS.get();
        String conversation = "planner-outage";

        AskResponse response = searchService.ask(new AskRequest("A Clash of Kings", conversation));

        assertThat(OfflinePlanner.PLANNER_CALLS.get()).isGreaterThan(callsBefore);
        assertThat(response.answer()).isNotBlank();
        CandidateRecorder.Capture capture = candidateRecorder.last(conversation).orElseThrow();
        // The raw question went through unchanged, with no filters, exactly as with the planner off.
        assertThat(capture.queryText()).isEqualTo("A Clash of Kings");
        assertThat(capture.candidateIds()).hasSize(20).first().isEqualTo("grrm-02");
    }
}
