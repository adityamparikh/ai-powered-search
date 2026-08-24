package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.embedding.EmbeddingService;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.SolrParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SearchRepository - schema-agnostic version.
 * <p>
 * Note: This version uses only _text_ catch-all field and doesn't assume
 * any specific field names exist in the schema.
 */
@ExtendWith(MockitoExtension.class)
class SearchRepositoryTest {

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

    // ==================== Hybrid Search Core ====================

    @Nested
    class HybridSearchCore {

        @Test
        void shouldPerformHybridSearchWithRRF() throws Exception {
            String collection = "test-collection";
            String query = "machine learning frameworks";
            int topK = 100;
            String vectorString = "[0.1, 0.2, 0.3, ...]";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

            SolrDocumentList documentList = new SolrDocumentList();
            SolrDocument doc1 = new SolrDocument();
            doc1.addField("id", "1");
            doc1.addField("content", "TensorFlow is a popular ML framework");
            doc1.addField("score", 0.95f);
            documentList.add(doc1);
            documentList.setNumFound(1L);

            when(queryResponse.getResults()).thenReturn(documentList);
            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST))).thenReturn(queryResponse);

            SearchResponse response = searchRepository.executeHybridRerankSearch(collection, query, topK, null, null, null);

            assertNotNull(response);
            assertEquals(1, response.documents().size());
        }

        @Test
        void shouldApplyFilterExpression() throws Exception {
            String collection = "test-collection";
            String query = "spring boot";
            int topK = 50;
            String filterExpression = "category:programming";
            String vectorString = "[0.1, 0.2, 0.3, ...]";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

            SolrDocumentList documentList = new SolrDocumentList();
            SolrDocument doc = new SolrDocument();
            doc.setField("id", "test-1");
            doc.setField("content", "Test document");
            documentList.add(doc);
            documentList.setNumFound(1L);

            when(queryResponse.getResults()).thenReturn(documentList);
            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST))).thenReturn(queryResponse);

            SearchResponse response = searchRepository.executeHybridRerankSearch(collection, query, topK, filterExpression, null, null);

            assertNotNull(response);
            assertEquals(1, response.documents().size());
        }

        @Test
        void shouldUseSchemaAgnosticFieldConfiguration() throws Exception {
            String collection = "test-collection";
            String query = "test query";
            int topK = 10;
            String vectorString = "[0.1, 0.2, 0.3, ...]";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

            SolrDocumentList documentList = new SolrDocumentList();
            SolrDocument doc = new SolrDocument();
            doc.setField("id", "test-1");
            doc.setField("content", "Test document");
            documentList.add(doc);
            documentList.setNumFound(1L);

            when(queryResponse.getResults()).thenReturn(documentList);
            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST))).thenReturn(queryResponse);

            searchRepository.executeHybridRerankSearch(collection, query, topK, null, null, null);

            // With client-side RRF, we make TWO separate queries (keyword + vector)
            ArgumentCaptor<SolrParams> queryCaptor = ArgumentCaptor.forClass(SolrParams.class);
            verify(solrClient, times(2)).query(eq(collection), queryCaptor.capture(), eq(SolrRequest.METHOD.POST));

            List<SolrParams> capturedQueries = queryCaptor.getAllValues();
            assertEquals(2, capturedQueries.size(), "Should make 2 queries: keyword and vector");

            // First query should be keyword search using edismax with _text_ field
            SolrParams keywordQuery = capturedQueries.get(0);
            assertEquals("edismax", keywordQuery.get("defType"),
                    "First query should use edismax");
            assertEquals("_text_", keywordQuery.get("qf"),
                    "Should use _text_ catch-all field for schema-agnostic keyword search");

            // Second query should be vector KNN search
            SolrParams vectorQuery = capturedQueries.get(1);
            String vectorQ = vectorQuery.get("q");
            assertTrue(vectorQ.contains("{!knn"),
                    "Second query should be KNN vector search: " + vectorQ);
        }
    }

    // ==================== Parallel Execution ====================

    @Nested
    class ParallelExecution {

        @Test
        void shouldExecuteBothSearchesConcurrently() throws Exception {
            String collection = "test-collection";
            String query = "concurrent query";
            String vectorString = "[0.1, 0.2, 0.3]";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

            SolrDocumentList kwDocs = new SolrDocumentList();
            SolrDocument kwDoc = new SolrDocument();
            kwDoc.setField("id", "kw-1");
            kwDoc.setField("score", 5.0f);
            kwDocs.add(kwDoc);
            kwDocs.setNumFound(1L);

            SolrDocumentList vecDocs = new SolrDocumentList();
            SolrDocument vecDoc = new SolrDocument();
            vecDoc.setField("id", "vec-1");
            vecDoc.setField("score", 0.9f);
            vecDocs.add(vecDoc);
            vecDocs.setNumFound(1L);

            // Both searches return different documents
            QueryResponse kwResponse = org.mockito.Mockito.mock(QueryResponse.class);
            QueryResponse vecResponse = org.mockito.Mockito.mock(QueryResponse.class);
            when(kwResponse.getResults()).thenReturn(kwDocs);
            when(vecResponse.getResults()).thenReturn(vecDocs);

            // Return different responses for keyword vs vector queries
            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenReturn(kwResponse, vecResponse);

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    collection, query, 10, null, null, null);

            assertNotNull(response);
            // Should have results from both searches
            assertFalse(response.documents().isEmpty());

            // Verify both queries were made
            verify(solrClient, times(2)).query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST));
        }

        @Test
        void shouldMergeResultsFromBothSearches() throws Exception {
            String collection = "test-collection";
            String query = "merge test";
            String vectorString = "[0.1, 0.2, 0.3]";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

            // Keyword search returns doc A and B
            SolrDocumentList kwDocs = new SolrDocumentList();
            SolrDocument kwA = new SolrDocument();
            kwA.setField("id", "A");
            kwA.setField("score", 10.0f);
            kwDocs.add(kwA);
            SolrDocument kwB = new SolrDocument();
            kwB.setField("id", "B");
            kwB.setField("score", 8.0f);
            kwDocs.add(kwB);
            kwDocs.setNumFound(2L);

            // Vector search returns doc A and C
            SolrDocumentList vecDocs = new SolrDocumentList();
            SolrDocument vecA = new SolrDocument();
            vecA.setField("id", "A");
            vecA.setField("score", 0.95f);
            vecDocs.add(vecA);
            SolrDocument vecC = new SolrDocument();
            vecC.setField("id", "C");
            vecC.setField("score", 0.85f);
            vecDocs.add(vecC);
            vecDocs.setNumFound(2L);

            QueryResponse kwResponse = org.mockito.Mockito.mock(QueryResponse.class);
            QueryResponse vecResponse = org.mockito.Mockito.mock(QueryResponse.class);
            when(kwResponse.getResults()).thenReturn(kwDocs);
            when(vecResponse.getResults()).thenReturn(vecDocs);

            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenReturn(kwResponse, vecResponse);

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    collection, query, 10, null, null, null);

            // Should have 3 unique documents: A (in both), B (keyword only), C (vector only)
            assertEquals(3, response.documents().size());

            // A should be ranked first (appears in both lists)
            assertEquals("A", response.documents().get(0).get("id"));
        }
    }

    // ==================== Over-Fetch Behavior ====================

    @Nested
    class OverFetchBehavior {

        @Test
        void shouldFetchDoubleTopKFromEachSearch() throws Exception {
            String collection = "test-collection";
            String query = "overfetch test";
            int topK = 25;
            String vectorString = "[0.1, 0.2, 0.3]";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

            SolrDocumentList emptyDocs = new SolrDocumentList();
            emptyDocs.setNumFound(0L);
            when(queryResponse.getResults()).thenReturn(emptyDocs);
            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenReturn(queryResponse);

            searchRepository.executeHybridRerankSearch(collection, query, topK, null, null, null);

            ArgumentCaptor<SolrParams> queryCaptor = ArgumentCaptor.forClass(SolrParams.class);
            // 2 from hybrid + 2 from fallback (keyword + vector) since hybrid returns empty
            verify(solrClient, times(4)).query(eq(collection), queryCaptor.capture(), eq(SolrRequest.METHOD.POST));

            // Check the first two queries (the hybrid ones) used 2x topK
            List<SolrParams> allQueries = queryCaptor.getAllValues();
            assertEquals("50", allQueries.get(0).get("rows"),
                    "Keyword search should fetch topK * 2 = 50 rows");
            assertEquals("50", allQueries.get(1).get("rows"),
                    "Vector search should fetch topK * 2 = 50 rows");
        }
    }

    // ==================== MinScore Filtering ====================

    @Nested
    class MinScoreFiltering {

        /**
         * minScore is a cosine similarity in [0..1]. Fused RRF scores are rank-derived and top out
         * near 2/61 with the default k=60, so applying minScore to them discarded every result for
         * any ordinary threshold and silently dropped the request into the fallback cascade.
         * Fusion output must survive a similarity-scaled threshold.
         */
        @Test
        void shouldNotDiscardFusedResultsForSimilarityScaledMinScore() throws Exception {
            String collection = "test-collection";
            String query = "score filter test";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn("[0.1, 0.2, 0.3]");

            QueryResponse keywordResponse = keywordResponse();
            QueryResponse vectorResponse = vectorResponse();
            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenReturn(keywordResponse, vectorResponse);

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    collection, query, 10, null, null, 0.5);

            // Both documents survive: RRF scores (~0.033 and ~0.016) are never compared to 0.5.
            assertEquals(2, response.documents().size());

            Map<String, Object> first = response.documents().get(0);
            Map<String, Object> second = response.documents().get(1);
            assertEquals("alpha", first.get("id"));
            assertEquals("beta", second.get("id"));

            // Presence of RRF bookkeeping proves these came from fusion, not the fallback cascade.
            assertNotNull(first.get("rrf_score"));
            assertNotNull(second.get("rrf_score"));
        }

        /**
         * The threshold still has to do something: it is the vector leg's similarity cutoff, so a
         * low-similarity hit must be excluded from the vector candidates before fusion.
         */
        @Test
        void shouldApplyMinScoreToVectorLegOnly() throws Exception {
            String collection = "test-collection";
            String query = "score filter test";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn("[0.1, 0.2, 0.3]");

            // Build the stubs before entering when(...) so their own stubbing completes first.
            QueryResponse keywordResponse = keywordResponse();
            QueryResponse vectorResponse = vectorResponse();
            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenReturn(keywordResponse, vectorResponse);

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    collection, query, 10, null, null, 0.5);

            Map<String, Object> alpha = response.documents().get(0);
            Map<String, Object> beta = response.documents().get(1);

            // alpha (vector similarity 0.9) clears the threshold and contributes from both legs.
            assertEquals(1, alpha.get("keyword_rank"));
            assertEquals(1, alpha.get("vector_rank"));

            // beta (vector similarity 0.2) is dropped from the vector leg but kept by BM25, whose
            // unbounded scores are not comparable to a [0..1] threshold.
            assertEquals(2, beta.get("keyword_rank"));
            assertNull(beta.get("vector_rank"));
        }

        /** BM25 scores are unbounded, so a similarity threshold must not filter keyword fallback. */
        @Test
        void shouldNotFilterKeywordFallbackByMinScore() throws Exception {
            String collection = "test-collection";
            String query = "fallback keeps low bm25";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn("[0.1, 0.2, 0.3]");

            QueryResponse emptyResponse = org.mockito.Mockito.mock(QueryResponse.class);
            when(emptyResponse.getResults()).thenReturn(emptyDocs());

            SolrDocumentList fallbackDocs = new SolrDocumentList();
            fallbackDocs.add(solrDoc("weak-but-relevant", 0.2f));
            fallbackDocs.setNumFound(1L);
            QueryResponse fallbackResponse = org.mockito.Mockito.mock(QueryResponse.class);
            when(fallbackResponse.getResults()).thenReturn(fallbackDocs);

            // hybrid keyword (empty), hybrid vector (empty), keyword fallback (low BM25 score)
            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenReturn(emptyResponse, emptyResponse, fallbackResponse);

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    collection, query, 10, null, null, 0.5);

            assertEquals(1, response.documents().size());
            assertEquals("weak-but-relevant", response.documents().get(0).get("id"));
        }

        /** The vector-only fallback is the one place the threshold keeps its filtering role. */
        @Test
        void shouldStillFilterVectorFallbackByMinScore() throws Exception {
            String collection = "test-collection";
            String query = "vector fallback below threshold";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn("[0.1, 0.2, 0.3]");

            QueryResponse emptyResponse = org.mockito.Mockito.mock(QueryResponse.class);
            when(emptyResponse.getResults()).thenReturn(emptyDocs());

            SolrDocumentList vectorDocs = new SolrDocumentList();
            vectorDocs.add(solrDoc("dissimilar", 0.2f));
            vectorDocs.setNumFound(1L);
            QueryResponse vectorFallback = org.mockito.Mockito.mock(QueryResponse.class);
            when(vectorFallback.getResults()).thenReturn(vectorDocs);

            // hybrid keyword, hybrid vector, keyword fallback all empty; vector fallback too weak
            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenReturn(emptyResponse, emptyResponse, emptyResponse, vectorFallback);

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    collection, query, 10, null, null, 0.5);

            assertTrue(response.documents().isEmpty());
        }

        private SolrDocument solrDoc(String id, float score) {
            SolrDocument doc = new SolrDocument();
            doc.setField("id", id);
            doc.setField("score", score);
            return doc;
        }

        private SolrDocumentList emptyDocs() {
            SolrDocumentList docs = new SolrDocumentList();
            docs.setNumFound(0L);
            return docs;
        }

        /** Two hits with unbounded BM25 scores, alpha ranked first. */
        private QueryResponse keywordResponse() {
            SolrDocumentList docs = new SolrDocumentList();
            docs.add(solrDoc("alpha", 8.0f));
            docs.add(solrDoc("beta", 3.0f));
            docs.setNumFound(2L);

            QueryResponse response = org.mockito.Mockito.mock(QueryResponse.class);
            when(response.getResults()).thenReturn(docs);
            return response;
        }

        /** The same two hits with cosine similarities straddling a 0.5 threshold. */
        private QueryResponse vectorResponse() {
            SolrDocumentList docs = new SolrDocumentList();
            docs.add(solrDoc("alpha", 0.9f));
            docs.add(solrDoc("beta", 0.2f));
            docs.setNumFound(2L);

            QueryResponse response = org.mockito.Mockito.mock(QueryResponse.class);
            when(response.getResults()).thenReturn(docs);
            return response;
        }

        @Test
        void shouldReturnAllResultsWhenMinScoreIsNull() throws Exception {
            String collection = "test-collection";
            String query = "no filter test";
            String vectorString = "[0.1, 0.2, 0.3]";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

            SolrDocumentList docs = new SolrDocumentList();
            SolrDocument doc = new SolrDocument();
            doc.setField("id", "1");
            doc.setField("score", 0.001f);
            docs.add(doc);
            docs.setNumFound(1L);

            when(queryResponse.getResults()).thenReturn(docs);
            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenReturn(queryResponse);

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    collection, query, 10, null, null, null);

            assertNotNull(response);
            assertEquals(1, response.documents().size());
        }
    }

    // ==================== Fallback Behavior ====================

    @Nested
    class FallbackBehavior {

        @Test
        void shouldFallbackToKeywordWhenHybridReturnsEmpty() throws Exception {
            String collection = "test-collection";
            String query = "fallback test";
            String vectorString = "[0.1, 0.2, 0.3]";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

            // First two calls (hybrid) return empty
            SolrDocumentList emptyDocs = new SolrDocumentList();
            emptyDocs.setNumFound(0L);

            // Third call (keyword fallback) returns results
            SolrDocumentList fallbackDocs = new SolrDocumentList();
            SolrDocument doc = new SolrDocument();
            doc.setField("id", "fallback-1");
            doc.setField("score", 5.0f);
            fallbackDocs.add(doc);
            fallbackDocs.setNumFound(1L);

            QueryResponse emptyResponse = org.mockito.Mockito.mock(QueryResponse.class);
            QueryResponse fallbackResponse = org.mockito.Mockito.mock(QueryResponse.class);
            when(emptyResponse.getResults()).thenReturn(emptyDocs);
            when(fallbackResponse.getResults()).thenReturn(fallbackDocs);

            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenReturn(emptyResponse, emptyResponse, fallbackResponse);

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    collection, query, 10, null, null, null);

            assertNotNull(response);
            assertFalse(response.documents().isEmpty());
            assertEquals("fallback-1", response.documents().get(0).get("id"));
        }

        @Test
        void shouldFallbackToVectorWhenKeywordFallbackFails() throws Exception {
            String collection = "test-collection";
            String query = "vector fallback";
            String vectorString = "[0.1, 0.2, 0.3]";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

            // Hybrid returns empty
            SolrDocumentList emptyDocs = new SolrDocumentList();
            emptyDocs.setNumFound(0L);

            // Vector fallback returns result
            SolrDocumentList vecDocs = new SolrDocumentList();
            SolrDocument vecDoc = new SolrDocument();
            vecDoc.setField("id", "vec-fallback");
            vecDoc.setField("score", 0.8f);
            vecDocs.add(vecDoc);
            vecDocs.setNumFound(1L);

            QueryResponse emptyResponse = org.mockito.Mockito.mock(QueryResponse.class);
            QueryResponse vecResponse = org.mockito.Mockito.mock(QueryResponse.class);
            when(emptyResponse.getResults()).thenReturn(emptyDocs);
            when(vecResponse.getResults()).thenReturn(vecDocs);

            // hybrid keyword (empty), hybrid vector (empty), keyword fallback (empty), vector fallback (result)
            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenReturn(emptyResponse, emptyResponse, emptyResponse, vecResponse);

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    collection, query, 10, null, null, null);

            assertNotNull(response);
            assertFalse(response.documents().isEmpty());
            assertEquals("vec-fallback", response.documents().get(0).get("id"));
        }

        @Test
        void shouldReturnEmptyWhenAllSearchStrategiesFail() throws Exception {
            String collection = "test-collection";
            String query = "total failure";
            String vectorString = "[0.1, 0.2, 0.3]";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

            SolrDocumentList emptyDocs = new SolrDocumentList();
            emptyDocs.setNumFound(0L);

            QueryResponse emptyResponse = org.mockito.Mockito.mock(QueryResponse.class);
            when(emptyResponse.getResults()).thenReturn(emptyDocs);

            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenReturn(emptyResponse);

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    collection, query, 10, null, null, null);

            assertNotNull(response);
            assertTrue(response.documents().isEmpty());
        }

        @Test
        void shouldFallbackWhenHybridSearchThrowsException() throws Exception {
            String collection = "test-collection";
            String query = "error test";
            String vectorString = "[0.1, 0.2, 0.3]";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

            // First two calls throw (hybrid), third call (keyword fallback) succeeds
            SolrDocumentList fallbackDocs = new SolrDocumentList();
            SolrDocument doc = new SolrDocument();
            doc.setField("id", "recovered");
            doc.setField("score", 3.0f);
            fallbackDocs.add(doc);
            fallbackDocs.setNumFound(1L);

            QueryResponse fallbackResponse = org.mockito.Mockito.mock(QueryResponse.class);
            when(fallbackResponse.getResults()).thenReturn(fallbackDocs);

            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenThrow(new SolrServerException("Solr is down"))
                    .thenThrow(new SolrServerException("Solr still down"))
                    .thenReturn(fallbackResponse);

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    collection, query, 10, null, null, null);

            assertNotNull(response);
            assertFalse(response.documents().isEmpty());
            assertEquals("recovered", response.documents().get(0).get("id"));
        }

        @Test
        void shouldReturnEmptyWhenEverythingFails() throws Exception {
            String collection = "test-collection";
            String query = "catastrophic failure";
            String vectorString = "[0.1, 0.2, 0.3]";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenThrow(new SolrServerException("All searches fail"));

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    collection, query, 10, null, null, null);

            assertNotNull(response);
            assertTrue(response.documents().isEmpty());
        }
    }

    // ==================== Field Selection ====================

    @Nested
    class FieldSelection {

        @Test
        void shouldPassFieldsCsvToQuery() throws Exception {
            String collection = "test-collection";
            String query = "field selection test";
            String fieldsCsv = "id,title,author";
            String vectorString = "[0.1, 0.2, 0.3]";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

            SolrDocumentList docs = new SolrDocumentList();
            SolrDocument doc = new SolrDocument();
            doc.setField("id", "1");
            doc.setField("title", "Test");
            doc.setField("score", 5.0f);
            docs.add(doc);
            docs.setNumFound(1L);

            when(queryResponse.getResults()).thenReturn(docs);
            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenReturn(queryResponse);

            searchRepository.executeHybridRerankSearch(collection, query, 10, null, fieldsCsv, null);

            ArgumentCaptor<SolrParams> queryCaptor = ArgumentCaptor.forClass(SolrParams.class);
            verify(solrClient, times(2)).query(eq(collection), queryCaptor.capture(), eq(SolrRequest.METHOD.POST));

            // Both queries should include the field list with score appended
            for (SolrParams params : queryCaptor.getAllValues()) {
                String fl = params.get("fl");
                assertTrue(fl.contains("id,title,author"),
                        "Should include requested fields: " + fl);
                assertTrue(fl.contains("score"),
                        "Should include score field: " + fl);
            }
        }

        @Test
        void shouldUseWildcardWhenFieldsCsvIsNull() throws Exception {
            String collection = "test-collection";
            String query = "wildcard fields";
            String vectorString = "[0.1, 0.2, 0.3]";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

            SolrDocumentList docs = new SolrDocumentList();
            SolrDocument doc = new SolrDocument();
            doc.setField("id", "1");
            doc.setField("score", 5.0f);
            docs.add(doc);
            docs.setNumFound(1L);

            when(queryResponse.getResults()).thenReturn(docs);
            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenReturn(queryResponse);

            searchRepository.executeHybridRerankSearch(collection, query, 10, null, null, null);

            ArgumentCaptor<SolrParams> queryCaptor = ArgumentCaptor.forClass(SolrParams.class);
            verify(solrClient, times(2)).query(eq(collection), queryCaptor.capture(), eq(SolrRequest.METHOD.POST));

            for (SolrParams params : queryCaptor.getAllValues()) {
                assertEquals("*,score", params.get("fl"),
                        "Should use wildcard with score when no fields specified");
            }
        }
    }

    // ==================== Filter Query ====================

    @Nested
    class FilterQuery {

        @Test
        void shouldPassFilterExpressionToBothSearches() throws Exception {
            String collection = "test-collection";
            String query = "filter test";
            String filter = "category:tech AND year:[2023 TO *]";
            String vectorString = "[0.1, 0.2, 0.3]";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

            SolrDocumentList docs = new SolrDocumentList();
            SolrDocument doc = new SolrDocument();
            doc.setField("id", "1");
            doc.setField("score", 5.0f);
            docs.add(doc);
            docs.setNumFound(1L);

            when(queryResponse.getResults()).thenReturn(docs);
            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenReturn(queryResponse);

            searchRepository.executeHybridRerankSearch(collection, query, 10, filter, null, null);

            ArgumentCaptor<SolrParams> queryCaptor = ArgumentCaptor.forClass(SolrParams.class);
            verify(solrClient, times(2)).query(eq(collection), queryCaptor.capture(), eq(SolrRequest.METHOD.POST));

            for (SolrParams params : queryCaptor.getAllValues()) {
                assertEquals(filter, params.get("fq"),
                        "Both searches should include the filter expression");
            }
        }

        @Test
        void shouldOmitFilterQueryWhenNull() throws Exception {
            String collection = "test-collection";
            String query = "no filter";
            String vectorString = "[0.1, 0.2, 0.3]";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

            SolrDocumentList docs = new SolrDocumentList();
            SolrDocument doc = new SolrDocument();
            doc.setField("id", "1");
            doc.setField("score", 5.0f);
            docs.add(doc);
            docs.setNumFound(1L);

            when(queryResponse.getResults()).thenReturn(docs);
            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenReturn(queryResponse);

            searchRepository.executeHybridRerankSearch(collection, query, 10, null, null, null);

            ArgumentCaptor<SolrParams> queryCaptor = ArgumentCaptor.forClass(SolrParams.class);
            verify(solrClient, times(2)).query(eq(collection), queryCaptor.capture(), eq(SolrRequest.METHOD.POST));

            for (SolrParams params : queryCaptor.getAllValues()) {
                assertNull(params.get("fq"),
                        "Should not include fq when filter is null");
            }
        }
    }

    // ==================== TopK Limiting ====================

    @Nested
    class TopKLimiting {

        @Test
        void shouldLimitResultsToTopK() throws Exception {
            String collection = "test-collection";
            String query = "limit test";
            int topK = 2;
            String vectorString = "[0.1, 0.2, 0.3]";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

            // Return 5 documents from keyword search
            SolrDocumentList docs = new SolrDocumentList();
            for (int i = 0; i < 5; i++) {
                SolrDocument doc = new SolrDocument();
                doc.setField("id", "doc-" + i);
                doc.setField("score", (float) (10.0 - i));
                docs.add(doc);
            }
            docs.setNumFound(5L);

            when(queryResponse.getResults()).thenReturn(docs);
            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenReturn(queryResponse);

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    collection, query, topK, null, null, null);

            assertNotNull(response);
            assertTrue(response.documents().size() <= topK,
                    "Should return at most topK results, got: " + response.documents().size());
        }
    }

    // ==================== Multi-Valued Fields ====================

    @Nested
    class MultiValuedFields {

        @Test
        void shouldExtractFirstValueFromMultiValuedFields() throws Exception {
            String collection = "test-collection";
            String query = "multivalue test";
            String vectorString = "[0.1, 0.2, 0.3]";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

            SolrDocumentList docs = new SolrDocumentList();
            SolrDocument doc = new SolrDocument();
            doc.setField("id", "1");
            doc.setField("score", 5.0f);
            doc.setField("tags", List.of("java", "spring", "ai"));
            docs.add(doc);
            docs.setNumFound(1L);

            when(queryResponse.getResults()).thenReturn(docs);
            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenReturn(queryResponse);

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    collection, query, 10, null, null, null);

            assertNotNull(response);
            assertEquals(1, response.documents().size());
            // Multi-valued field should have first value extracted
            assertEquals("java", response.documents().get(0).get("tags"));
        }
    }
}
