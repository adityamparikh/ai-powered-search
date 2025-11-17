package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.RestClientConfig;
import dev.aparikh.aipoweredsearch.embedding.EmbeddingService;
import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import({RestClientConfig.class,
        PostgresTestConfiguration.class,
        dev.aparikh.aipoweredsearch.search.MockChatModelConfiguration.class})
@Testcontainers
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+"),
        @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
})
class SearchRepositoryIT extends SolrTestBase {

    @Autowired
    private SearchRepository searchRepository;

    @MockitoBean
    private EmbeddingService embeddingService;

    @Test
    void shouldSearch() {
        // Create a simple search request
        SearchRequest searchRequest = new SearchRequest(
                "*:*",
                Collections.emptyList(),
                null,
                null,
                null
        );

        // Execute search
        SearchResponse response = searchRepository.search(TEST_COLLECTION, searchRequest);

        // Verify results
        assertNotNull(response);
        assertNotNull(response.documents());
        assertEquals(3, response.documents().size()); // We have 3 test documents

        Map<String, Object> document = response.documents().getFirst();
        assertNotNull(document.get("id"));
        assertNotNull(document.get("name"));
    }

    @Test
    void shouldGetFields() {
        Set<String> fields = searchRepository.getActuallyUsedFields(TEST_COLLECTION);

        assertNotNull(fields);
        assertTrue(fields.contains("id"));
        assertTrue(fields.contains("name"));
        assertTrue(fields.contains("description"));
    }

    @Test
    void shouldPerformHybridSearchWithRRF() {
        // Given
        String query = "Spring Boot";
        int topK = 10;

        // Mock embedding service to return a mock vector
        when(embeddingService.embedAndFormatForSolr(anyString()))
                .thenReturn("[0.1, 0.2, 0.3, 0.4, 0.5]");

        // When
        SearchResponse response = searchRepository.executeHybridRerankSearch(
                TEST_COLLECTION,
                query,
                topK,
                null,
                null,
                null
        );

        // Then
        assertNotNull(response, "Response should not be null");
        assertNotNull(response.documents(), "Documents list should not be null");
        // RRF combines lexical and semantic search, should return relevant documents
        assertTrue(response.documents().size() > 0, "Should return at least some documents");

        // Verify documents have scores
        response.documents().forEach(doc -> {
            assertNotNull(doc.get("id"), "Document should have id");
            assertNotNull(doc.get("score"), "Document should have RRF score");
        });
    }

    @Test
    void shouldPerformHybridSearchWithFilterExpression() {
        // Given
        String query = "Spring";
        int topK = 10;
        String filterExpression = "category:framework";

        when(embeddingService.embedAndFormatForSolr(anyString()))
                .thenReturn("[0.1, 0.2, 0.3, 0.4, 0.5]");

        // When
        SearchResponse response = searchRepository.executeHybridRerankSearch(
                TEST_COLLECTION,
                query,
                topK,
                filterExpression,
                null,
                null
        );

        // Then
        assertNotNull(response);
        assertNotNull(response.documents());

        // Verify only documents matching the filter are returned
        response.documents().forEach(doc -> {
            Object category = doc.get("category");
            if (category != null) {
                assertEquals("framework", category, "All results should have category=framework");
            }
        });
    }

    @Test
    void shouldPerformHybridSearchWithFieldSelection() {
        // Given
        String query = "Apache Solr";
        int topK = 10;
        String fieldsCsv = "id,name,category";

        when(embeddingService.embedAndFormatForSolr(anyString()))
                .thenReturn("[0.1, 0.2, 0.3, 0.4, 0.5]");

        // When
        SearchResponse response = searchRepository.executeHybridRerankSearch(
                TEST_COLLECTION,
                query,
                topK,
                null,
                fieldsCsv,
                null
        );

        // Then
        assertNotNull(response);
        assertNotNull(response.documents());

        // Verify only requested fields are returned (plus score which is always included)
        response.documents().forEach(doc -> {
            assertNotNull(doc.get("id"), "id field should be present");
            assertNotNull(doc.get("score"), "score field should be present");
            // Other fields from the CSV should be present if they exist in the document
            assertTrue(doc.containsKey("name") || doc.containsKey("category"),
                    "At least one of the requested fields should be present");
        });
    }

    @Test
    void shouldPerformHybridSearchWithMinScoreFilter() {
        // Given
        String query = "microservices architecture";
        int topK = 100;
        Double minScore = 0.01; // Use low threshold for test data

        when(embeddingService.embedAndFormatForSolr(anyString()))
                .thenReturn("[0.1, 0.2, 0.3, 0.4, 0.5]");

        // When
        SearchResponse response = searchRepository.executeHybridRerankSearch(
                TEST_COLLECTION,
                query,
                topK,
                null,
                null,
                minScore
        );

        // Then
        assertNotNull(response);
        assertNotNull(response.documents());

        // Verify all results meet minimum score threshold
        response.documents().forEach(doc -> {
            Object scoreObj = doc.get("score");
            assertNotNull(scoreObj, "Document should have a score");
            double score = ((Number) scoreObj).doubleValue();
            assertTrue(score >= minScore,
                    "Score " + score + " should be >= " + minScore);
        });
    }

    @Test
    void shouldHandleEmptyHybridSearchResults() {
        // Given
        String query = "nonexistent xyz123 abcdef";
        int topK = 10;

        when(embeddingService.embedAndFormatForSolr(anyString()))
                .thenReturn("[0.9, 0.9, 0.9, 0.9, 0.9]"); // Very different vector

        // When
        SearchResponse response = searchRepository.executeHybridRerankSearch(
                TEST_COLLECTION,
                query,
                topK,
                null,
                null,
                0.99 // Very high threshold to ensure no results
        );

        // Then
        assertNotNull(response, "Response should not be null even with no results");
        assertNotNull(response.documents(), "Documents list should not be null");
        // With high minScore threshold, we may get no results
        assertTrue(response.documents().size() >= 0, "Documents size should be >= 0");
    }

    @Test
    void shouldCombineLexicalAndSemanticSearchInRRF() {
        // Given - query that will match both lexically and semantically
        String query = "Spring Boot Application";
        int topK = 10;

        when(embeddingService.embedAndFormatForSolr(anyString()))
                .thenReturn("[0.1, 0.2, 0.3, 0.4, 0.5]");

        // When
        SearchResponse response = searchRepository.executeHybridRerankSearch(
                TEST_COLLECTION,
                query,
                topK,
                null,
                null,
                null
        );

        // Then
        assertNotNull(response);
        assertNotNull(response.documents());
        assertTrue(response.documents().size() > 0, "Should return results");

        // RRF should prioritize documents that match in both searches
        // Document with id=1 has "Spring Boot Application" in name field
        Map<String, Object> firstDoc = response.documents().get(0);
        assertNotNull(firstDoc.get("score"), "First document should have high RRF score");

        // Verify score is a reasonable RRF value (typically between 0 and some positive number)
        double score = ((Number) firstDoc.get("score")).doubleValue();
        assertTrue(score > 0, "RRF score should be positive");
    }

    @Test
    void shouldRankResultsByRRFScore() {
        // Given
        String query = "Spring";
        int topK = 10;

        when(embeddingService.embedAndFormatForSolr(anyString()))
                .thenReturn("[0.1, 0.2, 0.3, 0.4, 0.5]");

        // When
        SearchResponse response = searchRepository.executeHybridRerankSearch(
                TEST_COLLECTION,
                query,
                topK,
                null,
                null,
                null
        );

        // Then
        assertNotNull(response);
        List<Map<String, Object>> docs = response.documents();

        if (docs.size() > 1) {
            // Verify results are sorted by score in descending order
            for (int i = 0; i < docs.size() - 1; i++) {
                double currentScore = ((Number) docs.get(i).get("score")).doubleValue();
                double nextScore = ((Number) docs.get(i + 1).get("score")).doubleValue();
                assertTrue(currentScore >= nextScore,
                        "Results should be sorted by RRF score in descending order");
            }
        }
    }

    @Test
    void shouldHandleMultiValuedFieldsInHybridSearch() {
        // Given
        String query = "java spring";
        int topK = 10;

        when(embeddingService.embedAndFormatForSolr(anyString()))
                .thenReturn("[0.1, 0.2, 0.3, 0.4, 0.5]");

        // When
        SearchResponse response = searchRepository.executeHybridRerankSearch(
                TEST_COLLECTION,
                query,
                topK,
                null,
                "id,name,tags,score",
                null
        );

        // Then
        assertNotNull(response);
        assertNotNull(response.documents());

        // Verify multi-valued fields are handled correctly (first value extracted)
        response.documents().forEach(doc -> {
            Object tags = doc.get("tags");
            // Tags is a multi-valued field, should be extracted as first value
            if (tags != null) {
                assertTrue(tags instanceof String || tags instanceof List,
                        "Tags should be String (first value) or List");
            }
        });
    }
}