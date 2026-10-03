package dev.aparikh.aipoweredsearch.search.rag;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import dev.aparikh.aipoweredsearch.evaluation.CandidateRecorder;
import dev.aparikh.aipoweredsearch.evaluation.RagEvalData;
import dev.aparikh.aipoweredsearch.evaluation.RagEvalFixture;
import dev.aparikh.aipoweredsearch.evaluation.RagEvalTestConfiguration;
import dev.aparikh.aipoweredsearch.evaluation.RagMetrics;
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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.solr.SolrContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W1 against the real models: Claude Haiku as planner, Claude for generation and reranking,
 * OpenAI embeddings. Skipped without {@code ANTHROPIC_API_KEY} and {@code OPENAI_API_KEY}.
 */
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+"),
        @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
})
class QueryPlannerLiveIT {

    private static final Logger log = LoggerFactory.getLogger(QueryPlannerLiveIT.class);

    abstract static class FixtureBase {

        @Autowired
        SolrContainer solr;

        @Autowired
        SolrClient solrClient;

        @Autowired
        IndexService indexService;

        @Autowired
        SearchService searchService;

        @Autowired
        CandidateRecorder candidateRecorder;

        abstract String collection();

        @BeforeAll
        void indexFixture() throws Exception {
            RagEvalFixture.uploadConfigSet(solr);
            RagEvalFixture.createCollection(solrClient, collection());
            RagEvalFixture.index(indexService, solrClient, collection(), RagEvalData.fixture().books());
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "solr.default.collection=rag-planner-live",
            "search.rag.planner.enabled=true",
            "search.rag.planner.filters.enabled=true"})
    @Import({PostgresTestConfiguration.class, SolrTestConfiguration.class, RagEvalTestConfiguration.class})
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class RunningExample extends FixtureBase {

        @Override
        String collection() {
            return "rag-planner-live";
        }

        @Test
        void turnTwoRetrievesWhatTheStandaloneQueryRetrieves() {
            searchService.ask(new AskRequest(QueryPlannerIT.TURN_1, "live-turns"));
            searchService.ask(new AskRequest(QueryPlannerIT.TURN_2, "live-turns"));
            searchService.ask(new AskRequest(QueryPlannerIT.STANDALONE, "live-standalone"));

            List<String> turnTwo = candidateRecorder.lastCandidates("live-turns");
            List<String> standalone = candidateRecorder.lastCandidates("live-standalone");
            double parity = RagMetrics.parity(turnTwo, standalone);
            log.info("Turn-2 candidates {} vs standalone {}: parity {}", turnTwo, standalone, parity);

            assertThat(parity).isGreaterThanOrEqualTo(0.8);
            // The reranker judged against a rewrite that names the author, not "the same author".
            assertThat(candidateRecorder.last("live-turns").orElseThrow().queryText()).containsIgnoringCase("Martin");
        }
    }

    @Nested
    @AutoConfigureTestRestTemplate
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "solr.default.collection=rag-planner-outage",
            "search.rag.planner.enabled=true",
            "search.rag.planner.model=claude-model-that-does-not-exist"})
    @Import({PostgresTestConfiguration.class, SolrTestConfiguration.class, RagEvalTestConfiguration.class})
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class PlannerOutage extends FixtureBase {

        @LocalServerPort
        int port;

        @Autowired
        TestRestTemplate restTemplate;

        @Override
        String collection() {
            return "rag-planner-outage";
        }

        @Test
        void anInvalidPlannerModelStillAnswersWithTodaysRetrieval() {
            ResponseEntity<AskResponse> response = restTemplate.postForEntity(
                    "http://localhost:" + port + "/api/v1/search/ask",
                    new AskRequest("A Clash of Kings", "live-outage"), AskResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().answer()).isNotBlank();
            // Fallback: the raw question was retrieved and reranked as-is.
            assertThat(candidateRecorder.last("live-outage").orElseThrow().queryText()).isEqualTo("A Clash of Kings");
        }
    }
}
