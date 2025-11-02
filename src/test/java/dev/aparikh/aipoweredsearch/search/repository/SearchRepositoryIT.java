package dev.aparikh.aipoweredsearch.search.repository;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.search.SolrTestBase;
import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import({PostgresTestConfiguration.class, dev.aparikh.aipoweredsearch.search.MockChatModelConfiguration.class})
@org.testcontainers.junit.jupiter.Testcontainers
class SearchRepositoryIT extends SolrTestBase {
    
    @Autowired
    private SearchRepository searchRepository;

    @Test
    void shouldSearch() {
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
}