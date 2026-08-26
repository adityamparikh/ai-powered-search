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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

            // The legs run concurrently, so identify them by their parameters rather than by
            // capture order — which leg lands first is not part of the contract.
            SolrParams keywordQuery = capturedQueries.stream()
                    .filter(p -> "edismax".equals(p.get("defType")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No edismax keyword query was issued"));
            assertEquals("_text_", keywordQuery.get("qf"),
                    "Should use _text_ catch-all field for schema-agnostic keyword search");

            SolrParams vectorQuery = capturedQueries.stream()
                    .filter(p -> p.get("q") != null && p.get("q").contains("{!knn"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No KNN vector query was issued"));
            assertTrue(vectorQuery.get("q").contains("{!knn"),
                    "Vector leg should be a KNN search: " + vectorQuery.get("q"));
        }
    }

    // ==================== Parallel Execution ====================

    @Nested
    class ParallelExecution {

        /**
         * The keyword and vector legs are independent I/O calls on the critical path, so they
         * must overlap rather than run one after the other.
         *
         * <p>A CyclicBarrier proves it: each leg parks at the barrier and proceeds only once
         * the other arrives. Run sequentially, the first leg times out and breaks the barrier,
         * so neither records a meeting.</p>
         */
        @Test
        void shouldRunKeywordAndVectorLegsAtTheSameTime() throws Exception {
            String collection = "test-collection";
            String query = "concurrency test";

            when(embeddingService.embedAndFormatForSolr(query)).thenReturn("[0.1, 0.2, 0.3]");

            SolrDocumentList docs = new SolrDocumentList();
            SolrDocument doc = new SolrDocument();
            doc.setField("id", "1");
            doc.setField("score", 1.0f);
            docs.add(doc);
            docs.setNumFound(1L);
            when(queryResponse.getResults()).thenReturn(docs);

            CyclicBarrier barrier = new CyclicBarrier(2);
            AtomicInteger invocations = new AtomicInteger();
            AtomicInteger metConcurrently = new AtomicInteger();

            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenAnswer(invocation -> {
                        // Only the two hybrid legs participate; any fallback calls pass straight through.
                        if (invocations.incrementAndGet() <= 2) {
                            try {
                                barrier.await(2, TimeUnit.SECONDS);
                                metConcurrently.incrementAndGet();
                            } catch (Exception timedOutOrBroken) {
                                // Sequential execution lands here.
                            }
                        }
                        return queryResponse;
                    });

            searchRepository.executeHybridRerankSearch(collection, query, 10, null, null, null);

            assertEquals(2, metConcurrently.get(),
                    "Keyword and vector searches must be in flight at the same time");
        }

        @Test
        void shouldIssueBothKeywordAndVectorQueries() throws Exception {
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
         * Builds a SolrDocumentList from (id, score) pairs.
         */
        private SolrDocumentList docsWithScores(Object... idScorePairs) {
            SolrDocumentList docs = new SolrDocumentList();
            for (int i = 0; i < idScorePairs.length; i += 2) {
                SolrDocument doc = new SolrDocument();
                doc.setField("id", idScorePairs[i]);
                doc.setField("score", idScorePairs[i + 1]);
                docs.add(doc);
            }
            docs.setNumFound((long) (idScorePairs.length / 2));
            return docs;
        }

        private List<String> idsOf(SearchResponse response) {
            return response.documents().stream()
                    .map(d -> String.valueOf(d.get("id")))
                    .toList();
        }

        private void stubLegs(String collection, String query,
                             SolrDocumentList keywordDocs,
                             SolrDocumentList vectorDocs) throws Exception {
            when(embeddingService.embedAndFormatForSolr(query)).thenReturn("[0.1, 0.2, 0.3]");
            QueryResponse kwResponse = org.mockito.Mockito.mock(QueryResponse.class);
            QueryResponse vecResponse = org.mockito.Mockito.mock(QueryResponse.class);
            when(kwResponse.getResults()).thenReturn(keywordDocs);
            when(vecResponse.getResults()).thenReturn(vectorDocs);

            // Dispatch on the actual query rather than call order: the fallback cascade
            // issues extra calls, and an order-based stub would hand the keyword fallback
            // the vector response.
            when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                    .thenAnswer(invocation -> {
                        SolrParams params = invocation.getArgument(1);
                        return "edismax".equals(params.get("defType")) ? kwResponse : vecResponse;
                    });
        }

        @Test
        void appliesMinScoreToVectorLegBeforeFusion() throws Exception {
            String collection = "test-collection";
            String query = "vector leg filter";

            // Vector scores are cosine similarity in [0..1]; 0.2 is below the threshold.
            stubLegs(collection, query,
                    docsWithScores("kwOnly", 3.0f),
                    docsWithScores("vecHi", 0.9f, "vecLo", 0.2f));

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    collection, query, 10, null, null, 0.5);

            assertFalse(idsOf(response).contains("vecLo"),
                    "Vector hit below minScore must be dropped before fusion");
            assertTrue(idsOf(response).contains("vecHi"),
                    "Vector hit above minScore must survive");
        }

        @Test
        void doesNotApplyMinScoreToKeywordLeg() throws Exception {
            String collection = "test-collection";
            String query = "keyword leg unfiltered";

            // BM25 is unbounded, so a [0..1] threshold has no meaning for the keyword leg.
            stubLegs(collection, query,
                    docsWithScores("kwLow", 0.1f),
                    docsWithScores("vecHi", 0.9f));

            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    collection, query, 10, null, null, 0.5);

            assertTrue(idsOf(response).contains("kwLow"),
                    "Keyword hits must not be filtered by minScore; BM25 is not a [0..1] scale");
        }

        @Test
        void doesNotThresholdTheFusedRrfScore() throws Exception {
            String collection = "test-collection";
            String query = "fused score not thresholded";

            stubLegs(collection, query,
                    docsWithScores("A", 5.0f),
                    docsWithScores("B", 0.8f));

            // Every RRF score here is ~1/(60+1) = 0.0164, far below minScore=0.5.
            // Thresholding the fused score would wrongly empty the result set.
            SearchResponse response = searchRepository.executeHybridRerankSearch(
                    collection, query, 10, null, null, 0.5);

            assertEquals(2, response.documents().size(),
                    "RRF scores are rank-derived and must never be compared against minScore");
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
