package dev.aparikh.aipoweredsearch.indexing;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import dev.aparikh.aipoweredsearch.indexing.model.BatchIndexRequest;
import dev.aparikh.aipoweredsearch.indexing.model.IndexRequest;
import dev.aparikh.aipoweredsearch.indexing.model.IndexResponse;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Integration tests for IndexController and IndexService.
 * Tests document indexing with vector embeddings using Testcontainers.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import({PostgresTestConfiguration.class, SolrTestConfiguration.class})
class IndexIntegrationTest {

    @Autowired
    SolrContainer solrContainer;

    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SolrClient solrClient;

    @DynamicPropertySource
    static void configureSolrProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.api-key", () -> "test-key");
        registry.add("solr.default.collection", () -> COLLECTION);
    }

    private static final String COLLECTION = "test-collection";

    @BeforeEach
    void setUp() throws InterruptedException {

        // Create collection if it doesn't exist
        try {
            solrContainer.execInContainer("/opt/solr/bin/solr", "create_collection", "-c", COLLECTION, "-d", "_default");
        } catch (Exception e) {
            // Collection might already exist, ignore
        }

        // Wait a bit for collection to be ready
        Thread.sleep(2000);

        // Clear any existing documents
        try {
            solrClient.deleteByQuery(COLLECTION, "*:*");
            solrClient.commit(COLLECTION);
        } catch (Exception e) {
            // Ignore if collection doesn't exist yet
        }

        // Configure mock EmbeddingModel to return valid embeddings
        // Generate a simple 1536-dimensional vector (all 0.1 values)
        float[] mockEmbeddingVector = new float[1536];
        for (int i = 0; i < 1536; i++) {
            mockEmbeddingVector[i] = 0.1f;
        }

        when(embeddingModel.embedForResponse(anyList())).thenAnswer(invocation -> {
            List<?> texts = invocation.getArgument(0);
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                embeddings.add(new Embedding(mockEmbeddingVector, i));
            }
            return new EmbeddingResponse(embeddings);
        });
    }

    @Test
    void shouldIndexSingleDocument()  {
        // Given
        IndexRequest request = new IndexRequest(
                "doc-1",
                "Spring Boot is a powerful framework for building Java applications",
                Map.of("category", "framework", "language", "java")
        );

        // When
        ResponseEntity<IndexResponse> response = restTemplate.postForEntity(
                "http://localhost:{port}/api/v1/index/{collection}",
                request,
                IndexResponse.class,
                port,
                COLLECTION
        );

        // Debug output
        System.out.println("Response Status: " + response.getStatusCode());
        System.out.println("Response Body: " + response.getBody());

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().indexed());
        assertEquals(0, response.getBody().failed());
        assertThat(response.getBody().documentIds()).contains("doc-1");
        assertThat(response.getBody().message()).contains("Successfully indexed");

        // Verify document was indexed in Solr
        await().untilAsserted(() -> {
            QueryResponse queryResponse = solrClient.query(COLLECTION, new SolrQuery("id:doc-1"));
            SolrDocumentList results = queryResponse.getResults();
            assertEquals(1, results.getNumFound());
        });
    }

    @Test
    void shouldIndexDocumentWithAutoGeneratedId()  {
        // Given - no ID provided
        IndexRequest request = new IndexRequest(
                null,
                "Auto-generated ID test content",
                Map.of("test", "auto-id")
        );

        // When
        ResponseEntity<IndexResponse> response = restTemplate.postForEntity(
                "http://localhost:{port}/api/v1/index/{collection}",
                request,
                IndexResponse.class,
                port,
                COLLECTION
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().indexed());
        assertEquals(0, response.getBody().failed());

        // Should have a generated UUID
        List<String> documentIds = response.getBody().documentIds();
        assertThat(documentIds).hasSize(1);
        assertThat(documentIds.getFirst()).isNotEmpty();

        // Verify document was indexed
        String generatedId = documentIds.getFirst();
        await().untilAsserted(() -> {
            QueryResponse queryResponse = solrClient.query(COLLECTION, new SolrQuery("id:" + generatedId));
            assertEquals(1, queryResponse.getResults().getNumFound());
        });
    }

    @Test
    void shouldIndexDocumentWithMetadata()  {
        // Given
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("category", "database");
        metadata.put("version", "2.0");
        metadata.put("tags", List.of("sql", "nosql", "search"));
        metadata.put("published", true);

        IndexRequest request = new IndexRequest(
                "doc-with-metadata",
                "Document with rich metadata",
                metadata
        );

        // When
        ResponseEntity<IndexResponse> response = restTemplate.postForEntity(
                "http://localhost:{port}/api/v1/index/{collection}",
                request,
                IndexResponse.class,
                port,
                COLLECTION
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().indexed());

        // Verify metadata was indexed
        await().untilAsserted(() -> {
            QueryResponse queryResponse = solrClient.query(COLLECTION,
                    new SolrQuery("id:doc-with-metadata"));
            SolrDocumentList results = queryResponse.getResults();
            assertEquals(1, results.getNumFound());
            // Note: Metadata verification would depend on Solr schema configuration
        });
    }

    @Test
    void shouldBatchIndexMultipleDocuments()  {
        // Given
        List<IndexRequest> documents = List.of(
                new IndexRequest("batch-1", "First document in batch", Map.of("batch", "1")),
                new IndexRequest("batch-2", "Second document in batch", Map.of("batch", "2")),
                new IndexRequest("batch-3", "Third document in batch", Map.of("batch", "3"))
        );
        BatchIndexRequest batchRequest = new BatchIndexRequest(documents);

        // When
        ResponseEntity<IndexResponse> response = restTemplate.postForEntity(
                "http://localhost:{port}/api/v1/index/{collection}/batch",
                batchRequest,
                IndexResponse.class,
                port,
                COLLECTION
        );

        // Debug output
        System.out.println("Batch Response Status: " + response.getStatusCode());
        System.out.println("Batch Response Body: " + response.getBody());

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(3, response.getBody().indexed());
        assertEquals(0, response.getBody().failed());
        assertThat(response.getBody().documentIds())
                .containsExactlyInAnyOrder("batch-1", "batch-2", "batch-3");

        // Verify all documents were indexed
        await().untilAsserted(() -> {
            QueryResponse queryResponse = solrClient.query(COLLECTION, new SolrQuery("*:*"));
            assertThat(queryResponse.getResults().getNumFound()).isGreaterThanOrEqualTo(3);
        });
    }

    @Test
    void shouldBatchIndexWithMixedAutoAndManualIds()  {
        // Given - mix of documents with and without IDs
        List<IndexRequest> documents = List.of(
                new IndexRequest("manual-id-1", "Document with manual ID", null),
                new IndexRequest(null, "Document with auto ID", null),
                new IndexRequest("manual-id-2", "Another manual ID", null)
        );
        BatchIndexRequest batchRequest = new BatchIndexRequest(documents);

        // When
        ResponseEntity<IndexResponse> response = restTemplate.postForEntity(
                "http://localhost:{port}/api/v1/index/{collection}/batch",
                batchRequest,
                IndexResponse.class,
                port,
                COLLECTION
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(3, response.getBody().indexed());
        assertEquals(0, response.getBody().failed());

        List<String> documentIds = response.getBody().documentIds();
        assertThat(documentIds).hasSize(3);
        assertThat(documentIds).contains("manual-id-1", "manual-id-2");

        // Verify all documents were indexed
        await().untilAsserted(() -> {
            QueryResponse queryResponse = solrClient.query(COLLECTION, new SolrQuery("*:*"));
            assertThat(queryResponse.getResults().getNumFound()).isGreaterThanOrEqualTo(3);
        });
    }

    @Test
    void shouldHandleEmptyBatchRequest() {
        // Given - empty batch
        BatchIndexRequest batchRequest = new BatchIndexRequest(List.of());

        // When
        ResponseEntity<IndexResponse> response = restTemplate.postForEntity(
                "http://localhost:{port}/api/v1/index/{collection}/batch",
                batchRequest,
                IndexResponse.class,
                port,
                COLLECTION
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().indexed());
        assertEquals(0, response.getBody().failed());
    }

    @Test
    void shouldIndexDocumentWithEmptyMetadata() {
        // Given
        IndexRequest request = new IndexRequest(
                "doc-empty-metadata",
                "Document with no metadata",
                Map.of()
        );

        // When
        ResponseEntity<IndexResponse> response = restTemplate.postForEntity(
                "http://localhost:{port}/api/v1/index/{collection}",
                request,
                IndexResponse.class,
                port,
                COLLECTION
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().indexed());
        assertEquals(0, response.getBody().failed());
    }

    @Test
    void shouldIndexDocumentWithNullMetadata()  {
        // Given
        IndexRequest request = new IndexRequest(
                "doc-null-metadata",
                "Document with null metadata",
                null
        );

        // When
        ResponseEntity<IndexResponse> response = restTemplate.postForEntity(
                "http://localhost:{port}/api/v1/index/{collection}",
                request,
                IndexResponse.class,
                port,
                COLLECTION
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().indexed());
        assertEquals(0, response.getBody().failed());
    }

    @Test
    void shouldIndexLargeDocument()  {
        // Given - large content (simulate real-world scenario)
        StringBuilder largeContent = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            largeContent.append("This is line ").append(i).append(" of a large document. ");
        }

        IndexRequest request = new IndexRequest(
                "large-doc",
                largeContent.toString(),
                Map.of("size", "large", "lines", 100)
        );

        // When
        ResponseEntity<IndexResponse> response = restTemplate.postForEntity(
                "http://localhost:{port}/api/v1/index/{collection}",
                request,
                IndexResponse.class,
                port,
                COLLECTION
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().indexed());
        assertEquals(0, response.getBody().failed());

        // Verify large document was indexed
        await().untilAsserted(() -> {
            QueryResponse queryResponse = solrClient.query(COLLECTION, new SolrQuery("id:large-doc"));
            assertEquals(1, queryResponse.getResults().getNumFound());
        });
    }

    @Test
    void shouldHandleSpecialCharactersInContent() {
        // Given - content with special characters
        IndexRequest request = new IndexRequest(
                "special-chars-doc",
                "Content with special chars: @#$%^&*(){}[]|\\;:'\"<>?,./~`",
                Map.of("type", "special-chars")
        );

        // When
        ResponseEntity<IndexResponse> response = restTemplate.postForEntity(
                "http://localhost:{port}/api/v1/index/{collection}",
                request,
                IndexResponse.class,
                port,
                COLLECTION
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().indexed());
        assertEquals(0, response.getBody().failed());
    }

    @Test
    void shouldIndexDocumentsWithUnicodeContent()  {
        // Given - Unicode content
        IndexRequest request = new IndexRequest(
                "unicode-doc",
                "Unicode content: 你好世界 🌍 Здравствуй мир مرحبا بالعالم",
                Map.of("language", "multi")
        );

        // When
        ResponseEntity<IndexResponse> response = restTemplate.postForEntity(
                "http://localhost:{port}/api/v1/index/{collection}",
                request,
                IndexResponse.class,
                port,
                COLLECTION
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().indexed());
        assertEquals(0, response.getBody().failed());
    }

    @Test
    void shouldUpdateExistingDocument() {
        // Given - index initial document
        IndexRequest initialRequest = new IndexRequest(
                "update-doc",
                "Original content",
                Map.of("version", "1")
        );

        restTemplate.postForEntity(
                "http://localhost:{port}/api/v1/index/{collection}",
                initialRequest,
                IndexResponse.class,
                port,
                COLLECTION
        );

        // Wait for initial indexing
        await().untilAsserted(() -> {
            QueryResponse queryResponse = solrClient.query(COLLECTION, new SolrQuery("id:update-doc"));
            assertEquals(1, queryResponse.getResults().getNumFound());
        });

        // When - update with same ID
        IndexRequest updateRequest = new IndexRequest(
                "update-doc",
                "Updated content",
                Map.of("version", "2")
        );

        ResponseEntity<IndexResponse> response = restTemplate.postForEntity(
                "http://localhost:{port}/api/v1/index/{collection}",
                updateRequest,
                IndexResponse.class,
                port,
                COLLECTION
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().indexed());

        // Verify still only one document with that ID
        await().untilAsserted(() -> {
            QueryResponse queryResponse = solrClient.query(COLLECTION, new SolrQuery("id:update-doc"));
            assertEquals(1, queryResponse.getResults().getNumFound());
        });
    }

    @Test
    void shouldIndexDocumentsWithComplexMetadata()  {
        // Given - complex nested metadata structure
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("author", "John Doe");
        metadata.put("tags", List.of("java", "spring", "ai", "search"));
        metadata.put("priority", 5);
        metadata.put("published", true);
        metadata.put("rating", 4.5);

        IndexRequest request = new IndexRequest(
                "complex-metadata-doc",
                "Document with complex metadata structure",
                metadata
        );

        // When
        ResponseEntity<IndexResponse> response = restTemplate.postForEntity(
                "http://localhost:{port}/api/v1/index/{collection}",
                request,
                IndexResponse.class,
                port,
                COLLECTION
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().indexed());
        assertEquals(0, response.getBody().failed());
    }
}
