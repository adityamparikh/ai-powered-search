package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solr.SolrContainer;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import({PostgresTestConfiguration.class, SolrTestConfiguration.class})
class SearchIntegrationTest {

    @Autowired
    SolrContainer solrContainer;

    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean
    private SearchService searchService;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SolrClient solrClient;

    @DynamicPropertySource
    static void configureSolrProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.api-key", () -> "test-key");
    }

    private static final String COLLECTION = "test-collection";

    @BeforeEach
    void setUp() throws Exception {
        // Create collection if it doesn't exist
        try {
            solrContainer.execInContainer("/opt/solr/bin/solr", "create_collection", "-c", COLLECTION, "-d", "_default");
        } catch (Exception e) {
            // Collection might already exist, ignore
        }

        // Wait a bit for collection to be ready
        Thread.sleep(2000);

        // Add test document
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("id", "1");
        doc.addField("name", "Spring Boot Application");
        doc.addField("description", "A sample Spring Boot application with Solr integration");
        solrClient.add(COLLECTION, doc);
        solrClient.commit(COLLECTION);

        // Configure mock SearchService to return predefined results
        when(searchService.search(anyString(), anyString()))
            .thenReturn(new SearchResponse(
                java.util.List.of(java.util.Map.of(
                    "id", "1",
                    "name", "Spring Boot Application",
                    "description", "A sample Spring Boot application with Solr integration")),
                java.util.Collections.emptyMap()
            ));
    }

    @Test
    void shouldReturnSearchResults() {
        // When
        ResponseEntity<SearchResponse> response = restTemplate.getForEntity(
                "http://localhost:{port}/api/v1/search/{collection}?query={query}",
                SearchResponse.class,
                port,
                COLLECTION,
                "spring boot"
        );
        
        // Debug output
        System.out.println("Response Status: " + response.getStatusCode());
        System.out.println("Response Body: " + response.getBody());
        
        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().documents());
        assertEquals(1, response.getBody().documents().size());
        
        Map<String, Object> document = response.getBody().documents().getFirst();
        assertEquals("1", document.get("id"));
        assertEquals("Spring Boot Application", document.get("name"));
        assertEquals("A sample Spring Boot application with Solr integration", document.get("description"));
    }
}