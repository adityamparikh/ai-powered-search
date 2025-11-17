package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.embedding.EmbeddingService;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.SolrParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void shouldPerformHybridSearchWithRRF() throws Exception {
        // Given
        String collection = "test-collection";
        String query = "machine learning frameworks";
        int topK = 100;
        String vectorString = "[0.1, 0.2, 0.3, ...]";

        // Mock embedding service
        when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

        // Mock Solr response
        SolrDocumentList documentList = new SolrDocumentList();
        SolrDocument doc1 = new SolrDocument();
        doc1.addField("id", "1");
        doc1.addField("content", "TensorFlow is a popular ML framework");
        doc1.addField("score", 0.95f);
        documentList.add(doc1);

        SolrDocument doc2 = new SolrDocument();
        doc2.addField("id", "2");
        doc2.addField("content", "PyTorch for deep learning");
        doc2.addField("score", 0.88f);
        documentList.add(doc2);

        when(queryResponse.getResults()).thenReturn(documentList);
        when(queryResponse.getFacetFields()).thenReturn(null);
        when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST))).thenReturn(queryResponse);

        // When
        SearchResponse response = searchRepository.hybridSearch(collection, query, topK, null, null, null);

        // Then
        assertNotNull(response);
        assertEquals(2, response.documents().size());
        assertEquals("1", response.documents().get(0).get("id"));
        assertEquals("TensorFlow is a popular ML framework", response.documents().get(0).get("content"));
        assertEquals(0.95f, ((Number) response.documents().get(0).get("score")).floatValue());

        // Verify Solr query structure - should use reranking, not RRF (RRF not available in Solr 9.10.0)
        ArgumentCaptor<SolrParams> queryCaptor = ArgumentCaptor.forClass(SolrParams.class);
        verify(solrClient).query(eq(collection), queryCaptor.capture(), eq(SolrRequest.METHOD.POST));

        SolrParams capturedQuery = queryCaptor.getValue();
        // Verify keyword search component
        assertNotNull(capturedQuery.get("q"));
        assertEquals(query, capturedQuery.get("q"));
        assertEquals("edismax", capturedQuery.get("defType"));

        // Verify reranking component (not RRF, as it's not available in Solr 9.10.0)
        assertNotNull(capturedQuery.get("rq"));
        assertTrue(capturedQuery.get("rq").contains("rerank"), "Should use rerank query");
        assertNotNull(capturedQuery.get("vectorQ"));
        assertTrue(capturedQuery.get("vectorQ").contains("knn"), "vectorQ should contain knn query");

        assertEquals(String.valueOf(topK), capturedQuery.get("rows"));
    }

    @Test
    void shouldApplyFilterExpression() throws Exception {
        // Given
        String collection = "test-collection";
        String query = "spring boot";
        int topK = 50;
        String filterExpression = "category:programming AND year:[2020 TO *]";
        String vectorString = "[0.1, 0.2, 0.3, ...]";

        when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

        SolrDocumentList documentList = new SolrDocumentList();
        SolrDocument doc = new SolrDocument();
        doc.setField("id", "test-1");
        doc.setField("content", "Test document");
        documentList.add(doc);
        when(queryResponse.getResults()).thenReturn(documentList);
        when(queryResponse.getFacetFields()).thenReturn(null);
        when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST))).thenReturn(queryResponse);

        // When
        searchRepository.hybridSearch(collection, query, topK, filterExpression, null, null);

        // Then
        ArgumentCaptor<SolrParams> queryCaptor = ArgumentCaptor.forClass(SolrParams.class);
        verify(solrClient).query(eq(collection), queryCaptor.capture(), eq(SolrRequest.METHOD.POST));

        SolrParams capturedQuery = queryCaptor.getValue();
        String[] filterQueries = capturedQuery.getParams("fq");
        assertNotNull(filterQueries);
        assertEquals(1, filterQueries.length);
        assertEquals(filterExpression, filterQueries[0]);
    }

    @Test
    void shouldApplyFieldSelection() throws Exception {
        // Given
        String collection = "test-collection";
        String query = "java tutorials";
        int topK = 20;
        String fieldsCsv = "id,title,author";
        String vectorString = "[0.1, 0.2, 0.3, ...]";

        when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

        SolrDocumentList documentList = new SolrDocumentList();
        SolrDocument doc = new SolrDocument();
        doc.setField("id", "test-1");
        doc.setField("content", "Test document");
        documentList.add(doc);
        when(queryResponse.getResults()).thenReturn(documentList);
        when(queryResponse.getFacetFields()).thenReturn(null);
        when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST))).thenReturn(queryResponse);

        // When
        searchRepository.hybridSearch(collection, query, topK, null, fieldsCsv, null);

        // Then
        ArgumentCaptor<SolrParams> queryCaptor = ArgumentCaptor.forClass(SolrParams.class);
        verify(solrClient).query(eq(collection), queryCaptor.capture(), eq(SolrRequest.METHOD.POST));

        SolrParams capturedQuery = queryCaptor.getValue();
        String fields = capturedQuery.get("fl");
        assertEquals("id,title,author,score", fields);
    }

    @Test
    void shouldUseDefaultFieldsWhenNotSpecified() throws Exception {
        // Given
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
        when(queryResponse.getResults()).thenReturn(documentList);
        when(queryResponse.getFacetFields()).thenReturn(null);
        when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST))).thenReturn(queryResponse);

        // When
        searchRepository.hybridSearch(collection, query, topK, null, null, null);

        // Then
        ArgumentCaptor<SolrParams> queryCaptor = ArgumentCaptor.forClass(SolrParams.class);
        verify(solrClient).query(eq(collection), queryCaptor.capture(), eq(SolrRequest.METHOD.POST));

        SolrParams capturedQuery = queryCaptor.getValue();
        String fields = capturedQuery.get("fl");
        assertEquals("*,score", fields);
    }

    @Test
    void shouldApplyMinScoreFilter() throws Exception {
        // Given
        String collection = "test-collection";
        String query = "machine learning";
        int topK = 100;
        Double minScore = 0.75;
        String vectorString = "[0.1, 0.2, 0.3, ...]";

        when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

        // Create documents with varying scores
        SolrDocumentList documentList = new SolrDocumentList();

        SolrDocument doc1 = new SolrDocument();
        doc1.addField("id", "1");
        doc1.addField("content", "High score doc");
        doc1.addField("score", 0.95f);
        documentList.add(doc1);

        SolrDocument doc2 = new SolrDocument();
        doc2.addField("id", "2");
        doc2.addField("content", "Medium score doc");
        doc2.addField("score", 0.80f);
        documentList.add(doc2);

        SolrDocument doc3 = new SolrDocument();
        doc3.addField("id", "3");
        doc3.addField("content", "Low score doc");
        doc3.addField("score", 0.60f);
        documentList.add(doc3);

        when(queryResponse.getResults()).thenReturn(documentList);
        when(queryResponse.getFacetFields()).thenReturn(null);
        when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST))).thenReturn(queryResponse);

        // When
        SearchResponse response = searchRepository.hybridSearch(collection, query, topK, null, null, minScore);

        // Then
        assertNotNull(response);
        assertEquals(2, response.documents().size()); // Only docs with score >= 0.75

        // Verify all returned docs meet minimum score
        response.documents().forEach(doc -> {
            Object scoreObj = doc.get("score");
            assertNotNull(scoreObj);
            double score = ((Number) scoreObj).doubleValue();
            assertTrue(score >= minScore, "Score " + score + " should be >= " + minScore);
        });
    }

    @Test
    void shouldHandleMultiValuedFields() throws Exception {
        // Given
        String collection = "test-collection";
        String query = "test query";
        int topK = 10;
        String vectorString = "[0.1, 0.2, 0.3, ...]";

        when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

        // Create document with multi-valued fields
        SolrDocumentList documentList = new SolrDocumentList();
        SolrDocument doc = new SolrDocument();
        doc.addField("id", "1");
        doc.addField("tags", List.of("java", "spring", "boot")); // Multi-valued field
        doc.addField("score", 0.90f);
        documentList.add(doc);

        when(queryResponse.getResults()).thenReturn(documentList);
        when(queryResponse.getFacetFields()).thenReturn(null);
        when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST))).thenReturn(queryResponse);

        // When
        SearchResponse response = searchRepository.hybridSearch(collection, query, topK, null, null, null);

        // Then
        assertNotNull(response);
        assertEquals(1, response.documents().size());
        Map<String, Object> resultDoc = response.documents().get(0);

        // Verify multi-valued field extraction (should get first value)
        assertEquals("java", resultDoc.get("tags"));
    }

    @Test
    void shouldHandleEmptyResults() throws Exception {
        // Given
        String collection = "test-collection";
        String query = "nonexistent query";
        int topK = 10;
        String vectorString = "[0.1, 0.2, 0.3, ...]";

        when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

        // Return empty results for all queries (hybrid, keyword fallback, and vector fallback)
        SolrDocumentList emptyList = new SolrDocumentList();
        when(queryResponse.getResults()).thenReturn(emptyList);
        when(queryResponse.getFacetFields()).thenReturn(null);
        when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST))).thenReturn(queryResponse);

        // When
        SearchResponse response = searchRepository.hybridSearch(collection, query, topK, null, null, null);

        // Then
        assertNotNull(response);
        assertEquals(0, response.documents().size());
        assertNotNull(response.facetCounts());
        assertTrue(response.facetCounts().isEmpty());

        // Verify fallback attempts were made (hybrid -> keyword -> vector = 3 calls)
        verify(solrClient, times(3)).query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST));
    }

    @Test
    void shouldBuildCorrectKnnQuery() throws Exception {
        // Given
        String collection = "test-collection";
        String query = "semantic search query";
        int topK = 50;
        String vectorString = "[0.1, 0.2, 0.3, 0.4]";

        when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

        SolrDocumentList documentList = new SolrDocumentList();
        SolrDocument doc = new SolrDocument();
        doc.setField("id", "test-1");
        doc.setField("content", "Test document");
        documentList.add(doc);
        when(queryResponse.getResults()).thenReturn(documentList);
        when(queryResponse.getFacetFields()).thenReturn(null);
        when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST))).thenReturn(queryResponse);

        // When
        searchRepository.hybridSearch(collection, query, topK, null, null, null);

        // Then
        ArgumentCaptor<SolrParams> queryCaptor = ArgumentCaptor.forClass(SolrParams.class);
        verify(solrClient).query(eq(collection), queryCaptor.capture(), eq(SolrRequest.METHOD.POST));

        SolrParams capturedQuery = queryCaptor.getValue();

        // Verify KNN query is present in vectorQ parameter
        String vectorQ = capturedQuery.get("vectorQ");
        assertNotNull(vectorQ);
        assertTrue(vectorQ.contains("knn"), "vectorQ should contain 'knn': " + vectorQ);
        assertTrue(vectorQ.contains("vector"), "vectorQ should contain 'vector': " + vectorQ);
        assertTrue(vectorQ.contains("topK=" + topK), "vectorQ should contain topK: " + vectorQ);
    }

    @Test
    void shouldBuildCorrectEdismaxQuery() throws Exception {
        // Given
        String collection = "test-collection";
        String query = "spring boot tutorial";
        int topK = 100;
        String vectorString = "[0.1, 0.2, 0.3, ...]";

        when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

        SolrDocumentList documentList = new SolrDocumentList();
        SolrDocument doc = new SolrDocument();
        doc.setField("id", "test-1");
        doc.setField("content", "Test document");
        documentList.add(doc);
        when(queryResponse.getResults()).thenReturn(documentList);
        when(queryResponse.getFacetFields()).thenReturn(null);
        when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST))).thenReturn(queryResponse);

        // When
        searchRepository.hybridSearch(collection, query, topK, null, null, null);

        // Then
        ArgumentCaptor<SolrParams> queryCaptor = ArgumentCaptor.forClass(SolrParams.class);
        verify(solrClient).query(eq(collection), queryCaptor.capture(), eq(SolrRequest.METHOD.POST));

        SolrParams capturedQuery = queryCaptor.getValue();

        // Verify edismax query setup
        String q = capturedQuery.get("q");
        String defType = capturedQuery.get("defType");
        String qf = capturedQuery.get("qf");

        assertNotNull(q);
        assertEquals(query, q, "q should contain the query text");
        assertEquals("edismax", defType, "defType should be edismax");
        assertTrue(qf.contains("_text_"), "qf should include _text_ field: " + qf);
        assertTrue(qf.contains("content"), "qf should include content field: " + qf);
    }

    @Test
    void shouldHandleNullFilterExpression() throws Exception {
        // Given
        String collection = "test-collection";
        String query = "test";
        int topK = 10;
        String vectorString = "[0.1, 0.2, 0.3, ...]";

        when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

        SolrDocumentList documentList = new SolrDocumentList();
        SolrDocument doc = new SolrDocument();
        doc.setField("id", "test-1");
        doc.setField("content", "Test document");
        documentList.add(doc);
        when(queryResponse.getResults()).thenReturn(documentList);
        when(queryResponse.getFacetFields()).thenReturn(null);
        when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST))).thenReturn(queryResponse);

        // When
        searchRepository.hybridSearch(collection, query, topK, null, null, null);

        // Then - should not throw exception
        ArgumentCaptor<SolrParams> queryCaptor = ArgumentCaptor.forClass(SolrParams.class);
        verify(solrClient).query(eq(collection), queryCaptor.capture(), eq(SolrRequest.METHOD.POST));

        SolrParams capturedQuery = queryCaptor.getValue();
        String[] filterQueries = capturedQuery.getParams("fq");
        assertTrue(filterQueries == null || filterQueries.length == 0);
    }

    @Test
    void shouldHandleEmptyFilterExpression() throws Exception {
        // Given
        String collection = "test-collection";
        String query = "test";
        int topK = 10;
        String filterExpression = "";
        String vectorString = "[0.1, 0.2, 0.3, ...]";

        when(embeddingService.embedAndFormatForSolr(query)).thenReturn(vectorString);

        SolrDocumentList documentList = new SolrDocumentList();
        SolrDocument doc = new SolrDocument();
        doc.setField("id", "test-1");
        doc.setField("content", "Test document");
        documentList.add(doc);
        when(queryResponse.getResults()).thenReturn(documentList);
        when(queryResponse.getFacetFields()).thenReturn(null);
        when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST))).thenReturn(queryResponse);

        // When
        searchRepository.hybridSearch(collection, query, topK, filterExpression, null, null);

        // Then - should not add filter query
        ArgumentCaptor<SolrParams> queryCaptor = ArgumentCaptor.forClass(SolrParams.class);
        verify(solrClient).query(eq(collection), queryCaptor.capture(), eq(SolrRequest.METHOD.POST));

        SolrParams capturedQuery = queryCaptor.getValue();
        String[] filterQueries = capturedQuery.getParams("fq");
        assertTrue(filterQueries == null || filterQueries.length == 0);
    }
}