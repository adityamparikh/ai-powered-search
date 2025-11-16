package dev.aparikh.aipoweredsearch.solr.vectorstore;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.RestClientConfig;
import dev.aparikh.aipoweredsearch.config.SolrConfig;
import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.ai.openai.embedding.options.model=text-embedding-3-small",
                "spring.http.client.factory=simple"
        })
@Testcontainers
@Import({PostgresTestConfiguration.class, RestClientConfig.class, SolrConfig.class, SolrTestConfiguration.class, SolrVectorStoreIT.TestConfig.class})
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class SolrVectorStoreIT {

    private static final String COLLECTION_NAME = "test_collection";

    @Autowired
    SolrContainer solrContainer;

    @Autowired
    private SolrClient solrClient;

    @Autowired
    private VectorStore vectorStore;

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry registry) {
        registry.add("solr.default.collection", () -> COLLECTION_NAME);
    }

    private List<Document> documents;

    @BeforeEach
    void setUp() throws Exception {
        // Create collection (idempotent) and schema
        try {
            solrContainer.execInContainer("/opt/solr/bin/solr", "create_collection", "-c", COLLECTION_NAME, "-d", "_default");
        } catch (Exception ignored) {
        }
        // Add vector field type and field
        try {
            String addFieldTypeJson = """
                    {
                      "add-field-type": {
                        "name": "knn_vector_1536",
                        "class": "solr.DenseVectorField",
                        "vectorDimension": "1536",
                        "similarityFunction": "cosine",
                        "knnAlgorithm": "hnsw"
                      }
                    }
                    """;
            String addFieldJson = """
                    {
                      "add-field": {
                        "name": "vector",
                        "type": "knn_vector_1536",
                        "indexed": "true",
                        "stored": "true"
                      }
                    }
                    """;
            solrContainer.execInContainer("curl", "-X", "POST",
                    "http://localhost:8983/solr/" + COLLECTION_NAME + "/schema",
                    "-H", "Content-Type: application/json",
                    "-d", addFieldTypeJson);
            solrContainer.execInContainer("curl", "-X", "POST",
                    "http://localhost:8983/solr/" + COLLECTION_NAME + "/schema",
                    "-H", "Content-Type: application/json",
                    "-d", addFieldJson);
            Thread.sleep(500);
        } catch (Exception ignored) {
        }

        // Clean database before each test
        try {
            solrClient.deleteByQuery(COLLECTION_NAME, "*:*");
            solrClient.commit(COLLECTION_NAME);
        } catch (Exception ignored) {
        }

        // Initialize test documents
        documents = List.of(
                new Document("1", "Spring AI provides abstractions for AI models", Map.of("category", "AI", "year", 2024)),
                new Document("2", "Vector databases store embeddings for similarity search", Map.of("category", "Database", "year", 2024)),
                new Document("3", "Apache Solr supports dense vector fields for KNN search", Map.of("category", "Search", "year", 2023))
        );
    }

    @Test
    void addAndDeleteDocumentsTest() throws Exception {
        // Initially should have 0 documents
        long initialCount = solrClient.query(COLLECTION_NAME, new org.apache.solr.client.solrj.SolrQuery("*:*"))
                .getResults().getNumFound();
        assertThat(initialCount).isEqualTo(0);

        // Add documents
        vectorStore.add(documents);

        // Wait for indexing to complete
        await().untilAsserted(() -> {
            long count = solrClient.query(COLLECTION_NAME, new org.apache.solr.client.solrj.SolrQuery("*:*"))
                    .getResults().getNumFound();
            assertThat(count).isEqualTo(3);
        });

        // Delete documents
        vectorStore.delete(List.of("1", "2", "3"));

        // Wait for deletion to complete
        await().untilAsserted(() -> {
            long count = solrClient.query(COLLECTION_NAME, new org.apache.solr.client.solrj.SolrQuery("*:*"))
                    .getResults().getNumFound();
            assertThat(count).isEqualTo(0);
        });
    }

    @Test
    void addAndSearchTest() {
        // Add documents
        vectorStore.add(documents);

        // Wait for indexing
        await().untilAsserted(() -> {
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("AI abstractions")
                            .topK(5)
                            .build()
            );
            assertThat(results).isNotEmpty();
        });

        // Search for AI-related content
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("AI abstractions")
                        .topK(5)
                        .build()
        );

        assertThat(results).hasSizeGreaterThanOrEqualTo(1);

        // The first result should be the most relevant
        Document firstResult = results.get(0);
        assertThat(firstResult.getId()).isEqualTo("1");
        assertThat(firstResult.getText()).contains("Spring AI");

        // Verify metadata is preserved
        assertThat(firstResult.getMetadata()).containsEntry("category", "AI");

        // Verify score exists
        assertThat(firstResult.getMetadata()).containsKey("score");
    }

    @Test
    void searchWithFiltersTest() {
        // Add documents
        vectorStore.add(documents);

        // Wait for indexing
        await().untilAsserted(() -> {
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("database")
                            .topK(5)
                            .build()
            );
            assertThat(results).isNotEmpty();
        });

        // Test filter: category == "AI"
        List<Document> aiResults = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("technology")
                        .topK(5)
                        .filterExpression("category == 'AI'")
                        .build()
        );
        assertThat(aiResults).hasSize(1);
        assertThat(aiResults.get(0).getMetadata().get("category")).isEqualTo("AI");

        // Test filter: year == 2024
        List<Document> year2024Results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("information")
                        .topK(5)
                        .filterExpression("year == 2024")
                        .build()
        );
        assertThat(year2024Results).hasSize(2);

        // Test filter: year == 2023
        List<Document> year2023Results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("search")
                        .topK(5)
                        .filterExpression("year == 2023")
                        .build()
        );
        assertThat(year2023Results).hasSize(1);
        assertThat(year2023Results.get(0).getMetadata().get("category")).isEqualTo("Search");
    }

    @Test
    void documentUpdateTest() {
        // Add initial document
        Document originalDoc = new Document("update-test", "Original content", Map.of("version", 1));
        vectorStore.add(List.of(originalDoc));

        // Wait for indexing
        await().untilAsserted(() -> {
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("Original")
                            .topK(1)
                            .build()
            );
            assertThat(results).hasSize(1);
        });

        // Update document with same ID
        Document updatedDoc = new Document("update-test", "Updated content", Map.of("version", 2));
        vectorStore.add(List.of(updatedDoc));

        // Wait for update
        await().untilAsserted(() -> {
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("Updated")
                            .topK(1)
                            .build()
            );
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getText()).isEqualTo("Updated content");
            assertThat(results.get(0).getMetadata().get("version")).isEqualTo(2L);
        });
    }

    @Test
    void searchThresholdTest() {
        // Add documents
        vectorStore.add(documents);

        // Wait for indexing
        await().untilAsserted(() -> {
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("AI")
                            .topK(5)
                            .build()
            );
            assertThat(results).isNotEmpty();
        });

        // Get results without threshold
        List<Document> allResults = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("AI technology")
                        .topK(3)
                        .build()
        );

        assertThat(allResults).hasSizeGreaterThanOrEqualTo(2);

        // Get scores
        double score1 = ((Number) allResults.get(0).getMetadata().get("score")).doubleValue();
        double score2 = ((Number) allResults.get(1).getMetadata().get("score")).doubleValue();

        // Set threshold between first and second result
        double threshold = (score1 + score2) / 2.0;

        // Search with threshold
        List<Document> filteredResults = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("AI technology")
                        .topK(3)
                        .similarityThreshold(threshold)
                        .build()
        );

        // Should only get the top result
        assertThat(filteredResults).hasSize(1);
        assertThat(filteredResults.get(0).getId()).isEqualTo(allResults.get(0).getId());
    }

    @Test
    void overDefaultSizeTest() {
        // Create 15 documents
        List<Document> manyDocs = IntStream.range(0, 15)
                .mapToObj(i -> new Document(
                        "doc-" + i,
                        "Document number " + i + " about various topics",
                        Map.of("index", i)
                ))
                .toList();

        // Add documents
        vectorStore.add(manyDocs);

        // Wait for indexing
        await().untilAsserted(() -> {
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("topics")
                            .topK(15)
                            .build()
            );
            assertThat(results).hasSizeGreaterThanOrEqualTo(10);
        });

        // Search with topK > default size
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("topics")
                        .topK(15)
                        .build()
        );

        assertThat(results).hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    void getNativeClientTest() {
        // Get native client
        Optional<Object> nativeClient = vectorStore.getNativeClient();

        // Verify native client is available
        assertThat(nativeClient).isPresent();
        assertThat(nativeClient.get()).isInstanceOf(SolrClient.class);

        // Verify native client is functional
        SolrClient client = (SolrClient) nativeClient.get();
        assertThat(client).isNotNull();
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        EmbeddingModel embeddingModel(org.springframework.web.client.RestClient.Builder restClientBuilder) {
            var api = OpenAiApi.builder()
                    .apiKey(System.getenv("OPENAI_API_KEY"))
                    .restClientBuilder(restClientBuilder)
                    .build();
            return new OpenAiEmbeddingModel(api);
        }

        @Bean
        VectorStore vectorStore(SolrClient solrClient, EmbeddingModel embeddingModel) {
            return SolrVectorStore.builder(solrClient, COLLECTION_NAME, embeddingModel)
                    .options(SolrVectorStoreOptions.defaults())
                    .build();
        }
    }
}
