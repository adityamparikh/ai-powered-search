package dev.aparikh.aipoweredsearch.search.repository;

import dev.aparikh.aipoweredsearch.search.SolrTestBase;
import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class SolrSearchIntegrationTest extends SolrTestBase {

    @Autowired
    private SearchRepository searchRepository;

    @Test
    void shouldSearchWithSimpleQuery() {
        // Create a simple search request
        SearchRequest searchRequest = new SearchRequest(
                "*:*",
                Collections.emptyList(),
                null,
                null
        );
        
        // Execute search
        SearchResponse response = searchRepository.search(TEST_COLLECTION, searchRequest);
        
        // Verify results
        assertNotNull(response);
        assertNotNull(response.getDocuments());
        assertEquals(3, response.getDocuments().size()); // We have 3 test documents
        
        // Check that we have our test documents - name field comes as array from Solr
        boolean foundSpringBootApp = response.getDocuments().stream()
            .anyMatch(doc -> {
                Object nameField = doc.get("name");
                if (nameField instanceof List) {
                    List<?> nameList = (List<?>) nameField;
                    return nameList.contains("Spring Boot Application");
                }
                return "Spring Boot Application".equals(nameField);
            });
        assertTrue(foundSpringBootApp, "Should find the Spring Boot Application document");
    }
    
    @Test
    void shouldSearchWithFilterQuery() {
        // Create a search request with filter for documents containing "spring" in any field
        SearchRequest searchRequest = new SearchRequest(
                "*:*",
                List.of("tags:*spring*"),  // Both documents have "spring" in tags
                null,
                null
        );
        
        // Execute search
        SearchResponse response = searchRepository.search(TEST_COLLECTION, searchRequest);
        
        // Verify results - should find 2 documents (Spring Boot Application and Microservices Architecture)
        assertNotNull(response);
        assertNotNull(response.getDocuments());
        assertEquals(2, response.getDocuments().size());
    }
    
    @Test
    void shouldGetFields() {
        Set<String> fields = searchRepository.getActuallyUsedFields(TEST_COLLECTION);
        
        assertNotNull(fields);
        assertTrue(fields.contains("id"));
        assertTrue(fields.contains("name"));
        assertTrue(fields.contains("description"));
        assertTrue(fields.contains("category"));
        assertTrue(fields.contains("tags"));
    }
}