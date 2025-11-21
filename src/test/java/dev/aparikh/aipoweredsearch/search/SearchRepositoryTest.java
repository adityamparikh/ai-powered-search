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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

        // Then
        ArgumentCaptor<SolrParams> queryCaptor = ArgumentCaptor.forClass(SolrParams.class);
        verify(solrClient).query(eq(collection), queryCaptor.capture(), eq(SolrRequest.METHOD.POST));

        SolrParams capturedQuery = queryCaptor.getValue();

        // Verify it uses catch-all field (_text_) which works with any schema
        String q = capturedQuery.get("q");
        assertTrue(q.contains("{!edismax qf='_text_'}"),
                "Should use _text_ catch-all field for schema-agnostic search: " + q);
    }
}
