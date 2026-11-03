package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.config.SolrTestContainers;
import dev.aparikh.aipoweredsearch.embedding.EmbeddingService;
import dev.aparikh.aipoweredsearch.evaluation.RagEvalFixture;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.request.json.JsonQueryRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solr.SolrContainer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hybrid search against a real Solr 10.1, using the project configset (which declares the
 * {@code /hybrid} combined-query handler and fills {@code _text_}).
 *
 * <p>Needs Docker but no API keys: the query embedding is a fixed vector, so the fused order
 * is fully determined by the corpus below.</p>
 *
 * <p>Query: {@code "solr hybrid search"}, query vector {@code e0}. Cosine scores are Lucene's
 * {@code (1 + cos) / 2}.</p>
 * <pre>
 * id        content                           vector       cosine  keyword rank  vector rank
 * both      solr hybrid search                e0 + 0.5e1   0.947   1             2
 * kwOnly    solr hybrid search engine tuning  e0 + 2e2     0.724   2             3
 * vecOnly   gardening tips for spring         e0           1.000   -             1
 * neither   cooking pasta at home             e3           0.500   -             4
 * </pre>
 * <p>RRF (k=60): both = 1/61 + 1/62, kwOnly = 1/62 + 1/63, vecOnly = 1/61, neither = 1/64,
 * so the fused order is both, kwOnly, vecOnly, neither. That differs from either leg on its
 * own, which is what makes it a test of fusion.</p>
 */
@Testcontainers
class SolrHybridSearchIT {

    private static final String COLLECTION = "hybrid-it";
    private static final String NO_HANDLER_COLLECTION = "hybrid-it-default-configset";
    private static final String QUERY = "solr hybrid search";
    private static final int DIMENSIONS = 1536;

    @Container
    static final SolrContainer solr = SolrTestContainers.newSolrContainer();

    private static SolrClient solrClient;
    private static SearchRepository searchRepository;

    @BeforeAll
    static void setUp() throws Exception {
        solrClient = new HttpJdkSolrClient.Builder(
                "http://" + solr.getHost() + ":" + solr.getSolrPort() + "/solr").build();

        RagEvalFixture.uploadConfigSet(solr);
        CollectionAdminRequest.createCollection(COLLECTION, RagEvalFixture.CONFIGSET, 1, 1).process(solrClient);
        index(COLLECTION);

        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embedAndFormatForSolr(QUERY)).thenReturn(format(vector(Map.of(0, 1f))));
        searchRepository = new SearchRepository(solrClient, embeddingService);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (solrClient != null) {
            solrClient.close();
        }
    }

    @Test
    void fusesKeywordAndVectorRankingsInsideSolr() {
        SearchResponse response = searchRepository.executeHybridRerankSearch(
                COLLECTION, QUERY, 10, null, "id", null);

        assertThat(ids(response)).containsExactly("both", "kwOnly", "vecOnly", "neither");
    }

    @Test
    void reportsEachDocumentsRankInBothLegs() throws Exception {
        JsonQueryRequest request = searchRepository.buildHybridRequest(
                QUERY, format(vector(Map.of(0, 1f))), 10, null, "id", null);
        request.withParam("debug", "results");

        QueryResponse response = request.process(solrClient, COLLECTION);

        // Solr 10.1 pairs the ranks with query names in hash order rather than combiner.query
        // order, so the labels can be swapped. Assert on the ranks, not on which leg is named.
        Map<?, ?> explanations = (Map<?, ?>) response.getDebugMap().get("combinerExplanations");
        assertThat(String.valueOf(explanations.get("both")))
                .containsAnyOf("1/(60+1) + 1/(60+2)", "1/(60+2) + 1/(60+1)")
                .doesNotContain("not in the results");
        assertThat(String.valueOf(explanations.get("vecOnly")))
                .contains("= 1/(60+1) because")
                .contains("not in the results");
    }

    @Test
    void returnsTopKOfTheFusedList() {
        SearchResponse response = searchRepository.executeHybridRerankSearch(
                COLLECTION, QUERY, 2, null, "id", null);

        assertThat(ids(response)).containsExactly("both", "kwOnly");
    }

    @Test
    void filterAppliesToBothLegs() {
        SearchResponse response = searchRepository.executeHybridRerankSearch(
                COLLECTION, QUERY, 10, "-id:both", "id", null);

        assertThat(ids(response)).containsExactly("kwOnly", "vecOnly", "neither");
    }

    @Test
    void minScoreDropsDissimilarDocumentsFromTheVectorLegOnly() {
        // 0.8 keeps vecOnly (1.0) and both (0.947) in the vector leg; kwOnly (0.724) survives
        // through the keyword leg alone; neither (0.5) matches neither leg any more.
        SearchResponse response = searchRepository.executeHybridRerankSearch(
                COLLECTION, QUERY, 10, null, "id", 0.8);

        assertThat(ids(response)).containsExactly("both", "vecOnly", "kwOnly");
    }

    @Test
    void returnsRequestedFieldsAndNoMisleadingScore() {
        SearchResponse response = searchRepository.executeHybridRerankSearch(
                COLLECTION, QUERY, 10, null, "id,content", null);

        assertThat(response.documents()).hasSize(4).allSatisfy(doc ->
                assertThat(doc).containsOnlyKeys("id", "content"));
    }

    @Test
    void fallsBackWhenTheCollectionHasNoHybridHandler() throws Exception {
        // _default declares no /hybrid handler (Solr answers 404) and never fills _text_, so
        // the cascade lands on vector-only search.
        CollectionAdminRequest.createCollection(NO_HANDLER_COLLECTION, "_default", 1, 1).process(solrClient);
        String schema = """
                {
                  "add-field-type": {"name": "knn_vector_1536", "class": "solr.DenseVectorField",
                                     "vectorDimension": 1536, "similarityFunction": "cosine"},
                  "add-field": {"name": "vector", "type": "knn_vector_1536", "indexed": true, "stored": true}
                }
                """;
        solrClient.request(new GenericSolrRequest(SolrRequest.METHOD.POST, "/schema")
                .setRequiresCollection(true)
                .withContent(schema.getBytes(StandardCharsets.UTF_8), "application/json"), NO_HANDLER_COLLECTION);
        index(NO_HANDLER_COLLECTION);

        SearchResponse response = searchRepository.executeHybridRerankSearch(
                NO_HANDLER_COLLECTION, QUERY, 10, null, "id", null);

        assertThat(ids(response)).containsExactly("vecOnly", "both", "kwOnly", "neither");
    }

    private static void index(String collection) throws Exception {
        solrClient.add(collection, List.of(
                doc("both", "solr hybrid search", Map.of(0, 1f, 1, 0.5f)),
                doc("kwOnly", "solr hybrid search engine tuning", Map.of(0, 1f, 2, 2f)),
                doc("vecOnly", "gardening tips for spring", Map.of(0, 1f)),
                doc("neither", "cooking pasta at home", Map.of(3, 1f))));
        solrClient.commit(collection);
    }

    private static SolrInputDocument doc(String id, String content, Map<Integer, Float> vector) {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("id", id);
        doc.addField("content", content);
        doc.addField("vector", vector(vector));
        return doc;
    }

    private static List<Float> vector(Map<Integer, Float> nonZero) {
        List<Float> vector = new ArrayList<>(DIMENSIONS);
        IntStream.range(0, DIMENSIONS).forEach(i -> vector.add(nonZero.getOrDefault(i, 0f)));
        return vector;
    }

    private static String format(List<Float> vector) {
        return vector.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }

    private static List<Object> ids(SearchResponse response) {
        return response.documents().stream().map(d -> d.get("id")).toList();
    }
}
