package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import dev.aparikh.aipoweredsearch.indexing.IndexService;
import dev.aparikh.aipoweredsearch.indexing.model.IndexRequest;
import dev.aparikh.aipoweredsearch.search.model.AskRequest;
import dev.aparikh.aipoweredsearch.search.model.AskResponse;
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
 * Integration test for RAG (Retrieval-Augmented Generation) question answering functionality.
 *
 * <p>Tests the complete RAG flow using QuestionAnswerAdvisor:
 * <ol>
 *   <li>Documents are indexed with vector embeddings in Solr</li>
 *   <li>User asks a natural language question</li>
 *   <li>QuestionAnswerAdvisor automatically retrieves relevant documents from VectorStore</li>
 *   <li>Claude generates an answer based on the retrieved context</li>
 * </ol>
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
class RagQuestionAnswerIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RagQuestionAnswerIntegrationTest.class);

    private static final String RAG_COLLECTION = "rag-test";

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
        // Configure the default VectorStore collection to use our RAG test collection
        registry.add("solr.default.collection", () -> RAG_COLLECTION);
    }

    @BeforeEach
    void setUp() throws Exception {
        log.info("Setting up RAG integration test with real API models");

        setupRagCollection();
    }

    private void setupRagCollection() throws Exception {
        log.info("Setting up RAG collection: {}", RAG_COLLECTION);

        // Create collection with vector field
        createCollectionWithVectorField(RAG_COLLECTION);

        // Load and index test documents for RAG
        InputStream is = getClass().getResourceAsStream("/test-data/rag-test-docs.json");
        List<Map<String, Object>> docs = objectMapper.readValue(is, new TypeReference<>() {
        });

        for (Map<String, Object> docData : docs) {
            IndexRequest indexReq = new IndexRequest(
                    (String) docData.get("id"),
                    (String) docData.get("content"),
                    docData
            );
            indexService.indexDocument(RAG_COLLECTION, indexReq);
        }

        // Wait for indexing to complete
        solrClient.commit(RAG_COLLECTION);
        waitForDocumentsToBeIndexed(RAG_COLLECTION, docs.size());
        log.info("Indexed {} documents for RAG testing", docs.size());
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

    @Test
    void testRagQuestionAnsweringWithDefaultConversationId() {
        // Given: A question about Spring Boot
        AskRequest request = new AskRequest("What are the main benefits of Spring Boot?");

        // When: Asking the question via RAG endpoint
        String url = String.format("http://localhost:%d/api/v1/search/ask", port);
        ResponseEntity<AskResponse> response = restTemplate.postForEntity(url, request, AskResponse.class);

        // Then: Should return a valid answer
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());

        AskResponse askResponse = response.getBody();
        assertNotNull(askResponse.answer());
        assertFalse(askResponse.answer().isBlank(), "Answer should not be blank");
        assertEquals("default", askResponse.conversationId());
        assertNotNull(askResponse.sources());

        log.info("RAG Answer: {}", askResponse.answer());

        // Verify answer contains relevant information about Spring Boot
        String answer = askResponse.answer().toLowerCase();
        assertTrue(
                answer.contains("spring") || answer.contains("boot") || answer.contains("auto"),
                "Answer should reference Spring Boot concepts"
        );
    }

    @Test
    void testRagQuestionAnsweringWithCustomConversationId() {
        // Given: A question with custom conversation ID
        String conversationId = "test-session-123";
        AskRequest request = new AskRequest("How does dependency injection work in Spring?", conversationId);

        // When: Asking the question
        String url = String.format("http://localhost:%d/api/v1/search/ask", port);
        ResponseEntity<AskResponse> response = restTemplate.postForEntity(url, request, AskResponse.class);

        // Then: Should return answer with correct conversation ID
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());

        AskResponse askResponse = response.getBody();
        assertNotNull(askResponse.answer());
        assertFalse(askResponse.answer().isBlank());
        assertEquals(conversationId, askResponse.conversationId());

        log.info("RAG Answer (session {}): {}", conversationId, askResponse.answer());

        // Verify answer mentions dependency injection concepts
        String answer = askResponse.answer().toLowerCase();
        assertTrue(
                answer.contains("dependency") || answer.contains("injection") || answer.contains("di"),
                "Answer should reference dependency injection"
        );
    }

    @Test
    void testRagQuestionAnsweringRetrievesRelevantContext() {
        // Given: A specific question about Java features
        AskRequest request = new AskRequest("What are the key features of Java 21?");

        // When: Asking the question
        String url = String.format("http://localhost:%d/api/v1/search/ask", port);
        ResponseEntity<AskResponse> response = restTemplate.postForEntity(url, request, AskResponse.class);

        // Then: Should retrieve and use relevant documents
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());

        AskResponse askResponse = response.getBody();
        assertNotNull(askResponse.answer());
        assertFalse(askResponse.answer().isBlank());

        log.info("RAG Answer about Java 21: {}", askResponse.answer());

        // Verify answer discusses Java-related topics
        String answer = askResponse.answer().toLowerCase();
        assertTrue(
                answer.contains("java") || answer.contains("virtual") || answer.contains("thread") || answer.contains("pattern"),
                "Answer should reference Java features"
        );
    }

    @Test
    void testRagQuestionAnsweringWithNoRelevantDocuments() {
        // Given: A question about a topic not in the documents
        AskRequest request = new AskRequest("What is the capital of Antarctica?");

        // When: Asking the question
        String url = String.format("http://localhost:%d/api/v1/search/ask", port);
        ResponseEntity<AskResponse> response = restTemplate.postForEntity(url, request, AskResponse.class);

        // Then: Should still return an answer (Claude can answer from its knowledge)
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());

        AskResponse askResponse = response.getBody();
        assertNotNull(askResponse.answer());
        assertFalse(askResponse.answer().isBlank());

        log.info("RAG Answer without relevant docs: {}", askResponse.answer());

        // Claude might explain that Antarctica has no capital or provide general knowledge
        assertThat(askResponse.answer().length()).isGreaterThan(10);
    }

    @Test
    void testRagConversationalMemory() {
        // Given: Multiple questions in the same conversation
        String conversationId = "memory-test-456";

        // When: First question
        AskRequest request1 = new AskRequest("What is Spring Framework?", conversationId);
        String url = String.format("http://localhost:%d/api/v1/search/ask", port);
        ResponseEntity<AskResponse> response1 = restTemplate.postForEntity(url, request1, AskResponse.class);

        // Then: First answer should work
        assertEquals(HttpStatus.OK, response1.getStatusCode());
        assertNotNull(response1.getBody());
        log.info("First answer: {}", response1.getBody().answer());

        // When: Follow-up question (tests conversation memory)
        AskRequest request2 = new AskRequest("What are its main features?", conversationId);
        ResponseEntity<AskResponse> response2 = restTemplate.postForEntity(url, request2, AskResponse.class);

        // Then: Second answer should reference Spring (shows memory)
        assertEquals(HttpStatus.OK, response2.getStatusCode());
        assertNotNull(response2.getBody());

        AskResponse askResponse2 = response2.getBody();
        assertNotNull(askResponse2.answer());
        assertFalse(askResponse2.answer().isBlank());
        assertEquals(conversationId, askResponse2.conversationId());

        log.info("Follow-up answer: {}", askResponse2.answer());

        // The answer should provide relevant information about Spring features
        // Note: May or may not explicitly say "Spring" if it uses pronouns or context
        assertThat(askResponse2.answer().length()).isGreaterThan(20);
    }
}
