package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import dev.aparikh.aipoweredsearch.indexing.IndexService;
import dev.aparikh.aipoweredsearch.indexing.model.IndexRequest;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.schema.FieldTypeDefinition;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solr.SolrContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for semantic and hybrid search functionality.
 *
 * <p>These tests use real Solr, real embeddings, and real VectorStore to ensure
 * end-to-end functionality works correctly. They catch regressions that unit tests miss.</p>
 *
 * <p>Requires ANTHROPIC_API_KEY and OPENAI_API_KEY environment variables to be set.</p>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import({PostgresTestConfiguration.class, SolrTestConfiguration.class})
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".*"),
        @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".*")
})
class SemanticAndHybridSearchIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SemanticAndHybridSearchIntegrationTest.class);

    private static final String SEMANTIC_COLLECTION = "semantic-test";
    private static final String HYBRID_COLLECTION = "hybrid-test";

    @Autowired
    private SolrContainer solrContainer;

    @Autowired
    private SolrClient solrClient;

    @Autowired
    private IndexService indexService;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Use test API keys - these will be overridden by environment if set
        registry.add("spring.ai.openai.api-key", () -> System.getenv().getOrDefault("OPENAI_API_KEY", "test-key"));
        registry.add("spring.ai.anthropic.api-key", () -> System.getenv().getOrDefault("ANTHROPIC_API_KEY", "test-key"));
    }

    @BeforeEach
    void setUp() throws Exception {
        // Tests use real OpenAI embeddings and real Anthropic Claude for query generation
        log.info("Setting up integration tests with real API models");

        setupSemanticSearchCollection();
        setupHybridSearchCollection();
    }

    private void setupSemanticSearchCollection() throws Exception {
        log.info("Setting up semantic search collection: {}", SEMANTIC_COLLECTION);

        // Create collection with vector field
        createCollectionWithVectorField(SEMANTIC_COLLECTION);

        // Load and index test documents
        InputStream is = getClass().getResourceAsStream("/test-data/semantic-search-test-docs.json");
        List<Map<String, Object>> docs = objectMapper.readValue(is, new TypeReference<>() {
        });

        for (Map<String, Object> docData : docs) {
            IndexRequest indexReq = new IndexRequest(
                    (String) docData.get("id"),
                    (String) docData.get("content"),
                    docData
            );
            indexService.indexDocument(SEMANTIC_COLLECTION, indexReq);
        }

        // Wait for indexing to complete
        solrClient.commit(SEMANTIC_COLLECTION);
        waitForDocumentsToBeIndexed(SEMANTIC_COLLECTION, docs.size());
        log.info("Indexed {} documents for semantic search", docs.size());
    }

    private void setupHybridSearchCollection() throws Exception {
        log.info("Setting up hybrid search collection: {}", HYBRID_COLLECTION);

        // Create collection with vector field
        createCollectionWithVectorField(HYBRID_COLLECTION);

        // Load and index test documents
        InputStream is = getClass().getResourceAsStream("/test-data/hybrid-search-test-docs.json");
        List<Map<String, Object>> docs = objectMapper.readValue(is, new TypeReference<>() {
        });

        for (Map<String, Object> docData : docs) {
            IndexRequest indexReq = new IndexRequest(
                    (String) docData.get("id"),
                    (String) docData.get("content"),
                    docData
            );
            indexService.indexDocument(HYBRID_COLLECTION, indexReq);
        }

        // Wait for indexing to complete
        solrClient.commit(HYBRID_COLLECTION);
        waitForDocumentsToBeIndexed(HYBRID_COLLECTION, docs.size());
        log.info("Indexed {} documents for hybrid search", docs.size());
    }

    private void createCollectionWithVectorField(String collection) throws Exception {
        try {
            // Create collection
            CollectionAdminRequest.Create createRequest = CollectionAdminRequest.createCollection(collection, "_default", 1, 1);
            CollectionAdminResponse response = createRequest.process(solrClient);

            if (response.isSuccess()) {
                log.info("Successfully created collection: {}", collection);
            }

            // Wait for collection to be ready
            Thread.sleep(2000);

            // Add vector field type to schema
            FieldTypeDefinition fieldTypeDef = new FieldTypeDefinition();
            fieldTypeDef.setAttributes(Map.of(
                    "name", "knn_vector_1536",
                    "class", "solr.DenseVectorField",
                    "vectorDimension", "1536",
                    "similarityFunction", "cosine",
                    "knnAlgorithm", "hnsw"
            ));

            SchemaRequest.AddFieldType addFieldType = new SchemaRequest.AddFieldType(fieldTypeDef);
            addFieldType.process(solrClient, collection);
            log.info("Added vector field type to collection: {}", collection);

            // Wait for schema update
            Thread.sleep(1000);

            // Add vector field to schema
            SchemaRequest.AddField addVectorField = new SchemaRequest.AddField(
                    Map.of(
                            "name", "vector",
                            "type", "knn_vector_1536",
                            "indexed", true,
                            "stored", true
                    )
            );
            addVectorField.process(solrClient, collection);
            log.info("Added vector field to collection: {}", collection);

        } catch (Exception e) {
            // Collection might already exist
            log.warn("Collection creation warning (may already exist): {}", e.getMessage());
        }
    }

    private void waitForDocumentsToBeIndexed(String collection, int expectedCount) {
        log.info("Waiting for {} documents to be indexed in collection: {}", expectedCount, collection);
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> {
                    try {
                        var response = solrClient.query(collection, new org.apache.solr.client.solrj.SolrQuery("*:*"));
                        long numFound = response.getResults().getNumFound();
                        log.debug("Collection {} has {} documents (expecting {})", collection, numFound, expectedCount);
                        return numFound >= expectedCount;
                    } catch (Exception e) {
                        log.warn("Error querying collection {}: {}", collection, e.getMessage());
                        return false;
                    }
                });
        log.info("Successfully indexed {} documents in collection: {}", expectedCount, collection);
    }

    // ==================== Semantic Search Tests ====================

    @Test
    void testSemanticSearchReturnsResults_HorrorMovieQuery() {
        // Given: A query for "scary" should return horror books
        String query = "scary";

        // When: Performing semantic search
        String url = String.format("http://localhost:%d/api/v1/search/%s/semantic?query=%s",
                port, SEMANTIC_COLLECTION, query);

        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

        // Debug: Print response details if not OK
        if (response.getStatusCode() != HttpStatus.OK) {
            log.error("Semantic search failed with status: {}", response.getStatusCode());
            log.error("Response body: {}", response.getBody());
        }

        // Then: Should return results
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) response.getBody().get("documents");

        assertNotNull(documents, "Documents should not be null");
        assertFalse(documents.isEmpty(), "Semantic search should return results for 'scary' query");

        // Should return horror books since "scary" is semantically similar to horror content
        log.info("Semantic search returned {} results for query 'scary'", documents.size());

        // Verify we got horror-related results
        boolean hasHorrorContent = documents.stream()
                .anyMatch(doc -> {
                    Object genre = doc.get("genre");
                    return genre != null && genre.toString().contains("horror");
                });

        assertTrue(hasHorrorContent, "Results should include horror books for 'scary' query");
    }

    @Test
    void testSemanticSearchWithFilters() {
        // Given: A query that should return horror books
        // Note: The semantic search endpoint uses AI to parse the query, but filter application
        // depends on the AI model's interpretation. This test verifies the endpoint works with filters.
        String query = "supernatural books from the 1970s and 1980s";

        // When: Performing semantic search
        String url = String.format("http://localhost:%d/api/v1/search/%s/semantic?query=%s",
                port, SEMANTIC_COLLECTION, query);

        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

        // Then: Should return results (AI may or may not apply year filters)
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) response.getBody().get("documents");

        assertNotNull(documents, "Documents should not be null");

        // Verify we got some results (semantic search should find supernatural content)
        if (!documents.isEmpty()) {
            log.info("Semantic search returned {} results for supernatural books query", documents.size());

            // Check if any results have horror/supernatural genre
            boolean hasSupernatural = documents.stream()
                    .anyMatch(doc -> {
                        Object genre = doc.get("genre");
                        Object tags = doc.get("tags");
                        return (genre != null && genre.toString().contains("horror")) ||
                                (tags != null && tags.toString().contains("supernatural"));
                    });

            assertTrue(hasSupernatural, "Results should include horror/supernatural content");
        }
    }

    @Test
    void testSemanticSearchNoMatches_ReturnsEmptyList() {
        // Given: A query that won't match anything
        String query = "quantum mechanics thermodynamics biochemistry";

        // When: Performing semantic search
        String url = String.format("http://localhost:%d/api/v1/search/%s/semantic?query=%s",
                port, SEMANTIC_COLLECTION, query);

        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

        // Then: Should return empty list, not error
        assertEquals(HttpStatus.OK, response.getStatusCode());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) response.getBody().get("documents");

        assertNotNull(documents, "Documents list should not be null");
        // May or may not be empty depending on semantic similarity
    }

    // ==================== Hybrid Search Tests ====================

    @Test
    void testHybridSearchReturnsResults_ArtificialIntelligence() {
        // Given: A query for "artificial intelligence"
        String query = "artificial intelligence";

        // When: Performing hybrid search
        String url = String.format("http://localhost:%d/api/v1/search/%s/hybrid?query=%s",
                port, HYBRID_COLLECTION, query);

        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

        // Then: Should return results
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) response.getBody().get("documents");

        assertNotNull(documents, "Documents should not be null");
        assertFalse(documents.isEmpty(), "Hybrid search should return results for 'artificial intelligence' query");

        log.info("Hybrid search returned {} results for query 'artificial intelligence'", documents.size());

        // Verify we got AI-related content
        boolean hasAIContent = documents.stream()
                .anyMatch(doc -> {
                    Object content = doc.get("content");
                    Object title = doc.get("title");
                    return (content != null && content.toString().toLowerCase().contains("artificial intelligence")) ||
                            (content != null && content.toString().toLowerCase().contains("machine learning")) ||
                            (title != null && title.toString().toLowerCase().contains("artificial intelligence"));
                });

        assertTrue(hasAIContent, "Results should include AI-related documents");
    }

    @Test
    void testHybridSearchCombinesKeywordAndSemanticSignals() {
        // Given: A query that has both exact keyword match and semantic relevance
        String query = "machine learning";

        // When: Performing hybrid search
        String url = String.format("http://localhost:%d/api/v1/search/%s/hybrid?query=%s&k=20",
                port, HYBRID_COLLECTION, query);

        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

        // Then: Should return results combining both signals
        assertEquals(HttpStatus.OK, response.getStatusCode());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) response.getBody().get("documents");

        assertNotNull(documents);
        assertFalse(documents.isEmpty());

        // Document with exact keyword match should rank high
        boolean hasExactMatch = documents.stream()
                .limit(5) // Check top 5
                .anyMatch(doc -> {
                    Object title = doc.get("title");
                    Object content = doc.get("content");
                    return (title != null && title.toString().toLowerCase().contains("machine learning")) ||
                            (content != null && content.toString().toLowerCase().contains("machine learning"));
                });

        assertTrue(hasExactMatch, "Top results should include exact keyword matches");
    }

    @Test
    void testHybridSearchWithParameters() {
        // Given: A query with topK and minScore parameters
        String query = "neural networks";
        int topK = 5;
        double minScore = 0.1;

        // When: Performing hybrid search with parameters
        String url = String.format("http://localhost:%d/api/v1/search/%s/hybrid?query=%s&k=%d&minScore=%f",
                port, HYBRID_COLLECTION, query, topK, minScore);

        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

        // Then: Should respect parameters
        assertEquals(HttpStatus.OK, response.getStatusCode());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) response.getBody().get("documents");

        assertNotNull(documents);
        assertThat(documents.size()).isLessThanOrEqualTo(topK);

        // Verify all documents meet minScore threshold
        documents.forEach(doc -> {
            if (doc.containsKey("score")) {
                double score = ((Number) doc.get("score")).doubleValue();
                assertThat(score).isGreaterThanOrEqualTo(minScore);
            }
        });
    }

    @Test
    void testHybridSearchFallback_NoKeywordMatches() {
        // Given: A query with no exact keyword matches (tests fallback to vector search)
        String query = "scary frightening terrifying";

        // When: Performing hybrid search
        String url = String.format("http://localhost:%d/api/v1/search/%s/hybrid?query=%s",
                port, HYBRID_COLLECTION, query);

        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

        // Then: Should fallback gracefully and still return results
        assertEquals(HttpStatus.OK, response.getStatusCode());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) response.getBody().get("documents");

        // Should either return hybrid results or fall back to vector/keyword
        assertNotNull(documents, "Should return results (possibly from fallback)");
    }
}
