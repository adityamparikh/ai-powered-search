package dev.aparikh.aipoweredsearch.evaluation;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import dev.aparikh.aipoweredsearch.indexing.IndexService;
import dev.aparikh.aipoweredsearch.search.SearchRepository;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.common.SolrDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.solr.SolrContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that the evaluation fixture is indexed the way {@link RagEvaluationIT} assumes,
 * so the harness's numbers measure the pipeline rather than a broken setup.
 *
 * <p>This is also the evidence for W0 finding A7. Exact author matches and numeric price ranges
 * work on the explicit {@code metadata_author}/{@code metadata_price} fields, and the BM25 leg
 * is not silently empty. Needs {@code OPENAI_API_KEY} (one batched embedding request for 71
 * documents) but no Anthropic key.</p>
 */
@SpringBootTest(properties = "solr.default.collection=" + RagEvalFixtureIT.COLLECTION)
@Import({PostgresTestConfiguration.class, SolrTestConfiguration.class})
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagEvalFixtureIT {

    static final String COLLECTION = "rag-eval-fixture";

    @Autowired
    private SolrContainer solr;

    @Autowired
    private SolrClient solrClient;

    @Autowired
    private IndexService indexService;

    @Autowired
    private SearchRepository searchRepository;

    @BeforeAll
    void indexFixture() throws Exception {
        RagEvalFixture.uploadConfigSet(solr);
        RagEvalFixture.createCollection(solrClient, COLLECTION);
        RagEvalFixture.index(indexService, solrClient, COLLECTION, RagEvalData.fixture().books());
    }

    @Test
    void keywordLegMatchesContentBecauseTheProjectConfigsetFillsTextCatchAll() throws Exception {
        SolrQuery query = new SolrQuery("A Clash of Kings");
        query.set("defType", "edismax");
        query.set("qf", "_text_");

        List<String> ids = ids(solrClient.query(COLLECTION, query).getResults());

        assertThat(ids).isNotEmpty();
        assertThat(ids.getFirst()).isEqualTo("grrm-02");
    }

    @Test
    void authorFilterIsAnExactMatchOnTheStringField() throws Exception {
        SolrQuery query = new SolrQuery("*:*");
        query.addFilterQuery("metadata_author:\"George R.R. Martin\"");
        query.setRows(50);

        List<String> ids = ids(solrClient.query(COLLECTION, query).getResults());

        // Exact string match: no Robert C. Martin, no Martin Kleppmann, and no injection doc
        // whose *title* mentions George R.R. Martin.
        assertThat(ids).containsExactlyInAnyOrder(
                "grrm-01", "grrm-02", "grrm-03", "grrm-04", "grrm-05", "grrm-06", "grrm-07");
    }

    @Test
    void priceRangeIsNumericOnThePdoubleField() throws Exception {
        SolrQuery query = new SolrQuery("*:*");
        query.addFilterQuery("metadata_author:\"George R.R. Martin\"");
        query.addFilterQuery("metadata_price:[* TO 9]");
        query.setRows(50);

        List<String> ids = ids(solrClient.query(COLLECTION, query).getResults());

        // 8.99, 7.99 and 6.99. A lexical range would also let "10.99" and "12.99" through.
        assertThat(ids).containsExactlyInAnyOrder("grrm-02", "grrm-04", "grrm-07");
    }

    @Test
    void hybridSearchFusesBothLegsOverTheFixture() {
        SearchResponse response = searchRepository.executeHybridRerankSearch(
                COLLECTION, "A Clash of Kings", 20, null, "id,content,metadata_*", null);

        Map<String, Object> top = response.documents().getFirst();
        assertThat(top).containsEntry("id", "grrm-02");
        // Both legs contributed: the keyword leg is not silently empty.
        assertThat(top).containsKeys("keyword_rank", "vector_rank");
    }

    @Test
    void fixtureIsIndexedIntoExactlyOneSegmentWithAutomaticCommitsOff() throws Exception {
        // HNSW kNN is approximate per segment, so a reproducible vector-leg order needs one segment.
        String segments = solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/" + COLLECTION + "/admin/segments?wt=json").getStdout();
        String updateHandler = solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/" + COLLECTION + "/config/updateHandler?wt=json").getStdout();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode segmentInfo = mapper.readTree(segments).path("segments");
        assertThat(segmentInfo.size()).as(segments).isEqualTo(1);
        JsonNode handler = mapper.readTree(updateHandler).path("config").path("updateHandler");
        assertThat(handler.path("autoSoftCommit").path("maxTime").asInt()).as(updateHandler).isEqualTo(-1);
        assertThat(handler.path("autoCommit").path("maxTime").asInt()).as(updateHandler).isEqualTo(-1);
    }

    private static List<String> ids(List<SolrDocument> documents) {
        return documents.stream().map(d -> String.valueOf(d.getFieldValue("id"))).toList();
    }
}
