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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        documentList.setNumFound(1L);

        when(queryResponse.getResults()).thenReturn(documentList);
        when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST))).thenReturn(queryResponse);

        // When
        SearchResponse response = searchRepository.executeHybridRerankSearch(collection, query, topK, null, null, null);

        // Then
        assertNotNull(response);
        assertEquals(1, response.documents().size());
    }

    @Test
    void shouldApplyFilterExpression() throws Exception {
        // Given
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

        // When
        SearchResponse response = searchRepository.executeHybridRerankSearch(collection, query, topK, filterExpression, null, null);

        // Then
        assertNotNull(response);
        assertEquals(1, response.documents().size());
    }

    @Test
    void shouldUseSchemaAgnosticFieldConfiguration() throws Exception {
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
        documentList.setNumFound(1L);

        when(queryResponse.getResults()).thenReturn(documentList);
        when(solrClient.query(eq(collection), any(SolrParams.class), eq(SolrRequest.METHOD.POST))).thenReturn(queryResponse);

        // When
        searchRepository.executeHybridRerankSearch(collection, query, topK, null, null, null);

        // Then - With client-side RRF, we make TWO separate queries (keyword + vector)
        ArgumentCaptor<SolrParams> queryCaptor = ArgumentCaptor.forClass(SolrParams.class);
        verify(solrClient, times(2)).query(eq(collection), queryCaptor.capture(), eq(SolrRequest.METHOD.POST));

        // Get both captured queries
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
