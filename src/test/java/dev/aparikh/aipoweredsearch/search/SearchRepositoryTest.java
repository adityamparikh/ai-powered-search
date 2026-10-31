package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.embedding.EmbeddingService;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import org.apache.solr.client.solrj.RemoteSolrException;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.json.JsonQueryRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SearchRepository#executeHybridRerankSearch}.
 *
 * <p>Hybrid search is a single Solr combined-query request (server-side RRF). These tests pin
 * the request Solr receives and the fallback cascade taken when that request fails. The
 * fusion itself is Solr's and is covered by {@code SearchRepositoryIT}.</p>
 */
@ExtendWith(MockitoExtension.class)
class SearchRepositoryTest {

    private static final String COLLECTION = "test-collection";
    private static final String QUERY = "machine learning frameworks";
    private static final String VECTOR = "[0.1,0.2,0.3]";

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock
    private SolrClient solrClient;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private QueryResponse queryResponse;

    private SearchRepository searchRepository;

    @BeforeEach
    void setUp() {
        searchRepository = new SearchRepository(solrClient, embeddingService);
    }

    // ==================== Request shape ====================

    @Nested
    class CombinedQueryRequest {

        @Test
        void sendsOneRequestToTheHybridHandler() throws Exception {
            stubEmbedding();
            stubCombinedResults(doc("1"));

            searchRepository.executeHybridRerankSearch(COLLECTION, QUERY, 10, null, null, null);

            SolrRequest<?> request = captureCombinedRequest();
            assertThat(request).isInstanceOf(JsonQueryRequest.class);
            assertThat(request.getPath()).isEqualTo("/hybrid");
            assertThat(request.getMethod()).isEqualTo(SolrRequest.METHOD.POST);
            // No client-side legs: nothing goes through the plain query path.
            verify(solrClient, never()).query(any(String.class), any(SolrParams.class), any(SolrRequest.METHOD.class));
        }

        @Test
        void enablesRrfOverAKeywordAndAVectorLeg() throws Exception {
            stubEmbedding();
            stubCombinedResults(doc("1"));

            searchRepository.executeHybridRerankSearch(COLLECTION, QUERY, 10, null, null, null);

            JsonNode params = body(captureCombinedRequest()).path("params");
            assertThat(params.path("combiner").asBoolean()).isTrue();
            assertThat(params.path("combiner.algorithm").asString()).isEqualTo("rrf");
            assertThat(params.path("combiner.query").values())
                    .extracting(JsonNode::asString)
                    .containsExactly("keyword", "vector");
        }

        @Test
        void keywordLegIsEdismaxOverTheCatchAllField() throws Exception {
            stubEmbedding();
            stubCombinedResults(doc("1"));

            searchRepository.executeHybridRerankSearch(COLLECTION, QUERY, 10, null, null, null);

            JsonNode keyword = body(captureCombinedRequest()).path("queries").path("keyword").path("edismax");
            assertThat(keyword.path("query").asString()).isEqualTo(QUERY);
            assertThat(keyword.path("qf").asString()).isEqualTo("_text_");
        }

        @Test
        void vectorLegIsKnnOverTheEmbeddedQuery() throws Exception {
            stubEmbedding();
            stubCombinedResults(doc("1"));

            searchRepository.executeHybridRerankSearch(COLLECTION, QUERY, 10, null, null, null);

            JsonNode knn = body(captureCombinedRequest()).path("queries").path("vector").path("knn");
            assertThat(knn.path("f").asString()).isEqualTo("vector");
            assertThat(knn.path("query").asString()).isEqualTo(VECTOR);
            assertThat(knn.path("topK").asInt()).isEqualTo(20);
        }

        @Test
        void overFetchesCandidatesForFusionButReturnsTopK() throws Exception {
            stubEmbedding();
            stubCombinedResults(doc("1"));

            searchRepository.executeHybridRerankSearch(COLLECTION, QUERY, 7, null, null, null);

            JsonNode body = body(captureCombinedRequest());
            assertThat(body.path("limit").asInt()).isEqualTo(7);
            // Per-leg candidate depth is max(start + rows, shards.rows)
            assertThat(body.path("params").path("shards.rows").asInt())
                    .isEqualTo(7 * SearchRepository.OVER_FETCH_MULTIPLIER);
        }
    }

    // ==================== Filters and fields ====================

    @Nested
    class FiltersAndFields {

        @Test
        void filterAppliesToTheWholeCombinedRequest() throws Exception {
            stubEmbedding();
            stubCombinedResults(doc("1"));

            searchRepository.executeHybridRerankSearch(
                    COLLECTION, QUERY, 10, "metadata_year:[2020 TO *]", null, null);

            assertThat(body(captureCombinedRequest()).path("filter").values())
                    .extracting(JsonNode::asString)
                    .containsExactly("metadata_year:[2020 TO *]");
        }

        @Test
        void omitsFilterWhenNull() throws Exception {
            stubEmbedding();
            stubCombinedResults(doc("1"));

            searchRepository.executeHybridRerankSearch(COLLECTION, QUERY, 10, null, null, null);

            assertThat(body(captureCombinedRequest()).has("filter")).isFalse();
        }

        @Test
        void projectsRequestedFieldsWithoutAddingScore() throws Exception {
            stubEmbedding();
            stubCombinedResults(doc("1"));

            searchRepository.executeHybridRerankSearch(
                    COLLECTION, QUERY, 10, null, "id, content ,metadata_*", null);

            // Solr returns a raw per-leg score in combined mode, so it is not requested.
            assertThat(body(captureCombinedRequest()).path("fields").values())
                    .extracting(JsonNode::asString)
                    .containsExactly("id", "content", "metadata_*");
        }

        @Test
        void leavesFieldListToSolrWhenFieldsCsvIsNull() throws Exception {
            stubEmbedding();
            stubCombinedResults(doc("1"));

            searchRepository.executeHybridRerankSearch(COLLECTION, QUERY, 10, null, null, null);

            assertThat(body(captureCombinedRequest()).has("fields")).isFalse();
        }
    }

    // ==================== minScore ====================

    @Nested
    class MinScore {

        @Test
        void thresholdsTheVectorLegWithVectorSimilarity() throws Exception {
            stubEmbedding();
            stubCombinedResults(doc("1"));

            searchRepository.executeHybridRerankSearch(COLLECTION, QUERY, 10, null, null, 0.8);

            JsonNode vector = body(captureCombinedRequest()).path("queries").path("vector");
            assertThat(vector.has("knn")).isFalse();
            JsonNode similarity = vector.path("vectorSimilarity");
            assertThat(similarity.path("f").asString()).isEqualTo("vector");
            assertThat(similarity.path("minReturn").asDouble()).isEqualTo(0.8);
            assertThat(similarity.path("query").asString()).isEqualTo(VECTOR);
        }

        @Test
        void neverThresholdsTheKeywordLeg() throws Exception {
            stubEmbedding();
            stubCombinedResults(doc("1"));

            searchRepository.executeHybridRerankSearch(COLLECTION, QUERY, 10, null, null, 0.8);

            JsonNode keyword = body(captureCombinedRequest()).path("queries").path("keyword");
            assertThat(keyword.toString()).doesNotContain("0.8");
        }
    }

    // ==================== Results ====================

    @Nested
    class Results {

        @Test
        void preservesSolrsFusedOrder() throws Exception {
            stubEmbedding();
            stubCombinedResults(doc("b"), doc("a"), doc("c"));

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    COLLECTION, QUERY, 10, null, null, null);

            assertThat(response.documents()).extracting(d -> d.get("id"))
                    .containsExactly("b", "a", "c");
            assertThat(response.facetCounts()).isEmpty();
            assertThat(response.spellCheckSuggestion()).isNull();
        }

        @Test
        void extractsFirstValueFromMultiValuedFields() throws Exception {
            stubEmbedding();
            SolrDocument doc = doc("1");
            doc.addField("tags", List.of("java", "spring"));
            stubCombinedResults(doc);

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    COLLECTION, QUERY, 10, null, null, null);

            assertThat(response.documents().getFirst()).containsEntry("tags", "java");
        }
    }

    // ==================== Fallback ====================

    @Nested
    class Fallback {

        @Test
        void fallsBackToKeywordWhenTheCombinedRequestFails() throws Exception {
            stubEmbedding();
            // e.g. a collection whose configset lacks the /hybrid handler (Solr answers 404)
            when(solrClient.request(any(SolrRequest.class), eq(COLLECTION)))
                    .thenThrow(new SolrServerException("404 Not Found"));
            stubPlainQueryResults(doc("kw"));

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    COLLECTION, QUERY, 10, null, null, null);

            assertThat(response.documents()).extracting(d -> d.get("id")).containsExactly("kw");
        }

        @Test
        void fallsBackWhenSolrReturnsAServerError() throws Exception {
            stubEmbedding();
            when(solrClient.request(any(SolrRequest.class), eq(COLLECTION)))
                    .thenThrow(new RemoteSolrException("http://solr", 503, "Service Unavailable", null));
            stubPlainQueryResults(doc("kw"));

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    COLLECTION, QUERY, 10, null, null, null);

            assertThat(response.documents()).extracting(d -> d.get("id")).containsExactly("kw");
        }

        @Test
        void fallsBackWhenTheCollectionHasNoHybridHandler() throws Exception {
            stubEmbedding();
            when(solrClient.request(any(SolrRequest.class), eq(COLLECTION)))
                    .thenThrow(new RemoteSolrException("http://solr", 404, "Not Found", null));
            stubPlainQueryResults(doc("kw"));

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    COLLECTION, QUERY, 10, null, null, null);

            assertThat(response.documents()).extracting(d -> d.get("id")).containsExactly("kw");
        }

        @Test
        void fallsBackToKeywordWhenTheQueryCannotBeEmbedded() throws Exception {
            when(embeddingService.embedAndFormatForSolr(QUERY))
                    .thenThrow(new IllegalStateException("embedding model unavailable"));
            stubPlainQueryResults(doc("kw"));

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    COLLECTION, QUERY, 10, null, null, null);

            assertThat(response.documents()).extracting(d -> d.get("id")).containsExactly("kw");
            verify(solrClient, never()).request(any(SolrRequest.class), any(String.class));
        }

        @Test
        void fallsBackToVectorWhenKeywordFallbackIsEmpty() throws Exception {
            stubEmbedding();
            when(solrClient.request(any(SolrRequest.class), eq(COLLECTION)))
                    .thenThrow(new IOException("connection reset"));
            SolrDocument vectorHit = doc("vec");
            vectorHit.addField("score", 0.9f);
            SolrDocumentList empty = new SolrDocumentList();
            SolrDocumentList vectorHits = new SolrDocumentList();
            vectorHits.add(vectorHit);
            when(queryResponse.getResults()).thenReturn(empty, vectorHits);
            when(solrClient.query(eq(COLLECTION), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenReturn(queryResponse);

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    COLLECTION, QUERY, 10, null, null, null);

            assertThat(response.documents()).extracting(d -> d.get("id")).containsExactly("vec");
            verify(solrClient, times(2)).query(eq(COLLECTION), any(SolrParams.class), eq(SolrRequest.METHOD.POST));
        }

        @Test
        void returnsEmptyWhenEverythingFails() throws Exception {
            stubEmbedding();
            when(solrClient.request(any(SolrRequest.class), eq(COLLECTION)))
                    .thenThrow(new SolrServerException("down"));
            when(solrClient.query(eq(COLLECTION), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenThrow(new SolrServerException("down"));

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    COLLECTION, QUERY, 10, null, null, null);

            assertThat(response.documents()).isEmpty();
        }

        @Test
        void doesNotFallBackWhenSolrFindsNothing() throws Exception {
            stubEmbedding();
            stubCombinedResults();

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    COLLECTION, QUERY, 10, null, null, null);

            // An empty fusion means both legs were empty; repeating each leg alone cannot help.
            assertThat(response.documents()).isEmpty();
            verify(solrClient, never()).query(any(String.class), any(SolrParams.class), any(SolrRequest.METHOD.class));
        }
    }

    // ==================== helpers ====================

    private void stubEmbedding() {
        when(embeddingService.embedAndFormatForSolr(QUERY)).thenReturn(VECTOR);
    }

    private void stubCombinedResults(SolrDocument... docs) throws Exception {
        SolrDocumentList list = new SolrDocumentList();
        list.addAll(List.of(docs));
        list.setNumFound(docs.length);
        NamedList<Object> raw = new NamedList<>();
        raw.add("response", list);
        when(solrClient.request(any(SolrRequest.class), eq(COLLECTION))).thenReturn(raw);
    }

    private void stubPlainQueryResults(SolrDocument... docs) throws Exception {
        SolrDocumentList list = new SolrDocumentList();
        list.addAll(List.of(docs));
        when(queryResponse.getResults()).thenReturn(list);
        when(solrClient.query(eq(COLLECTION), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                .thenReturn(queryResponse);
    }

    private SolrRequest<?> captureCombinedRequest() throws Exception {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<SolrRequest<?>> captor = (ArgumentCaptor) ArgumentCaptor.forClass(SolrRequest.class);
        verify(solrClient).request(captor.capture(), eq(COLLECTION));
        return captor.getValue();
    }

    private JsonNode body(SolrRequest<?> request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        request.getContentWriter("application/json").write(out);
        return mapper.readTree(out.toByteArray());
    }

    private static SolrDocument doc(String id) {
        SolrDocument doc = new SolrDocument();
        doc.addField("id", id);
        doc.addField("_text_", "content " + id);
        return doc;
    }
}
