package dev.aparikh.aipoweredsearch.solr.vectorstore;

import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for SolrVectorStore.
 * Based on ElasticsearchVectorStoreIT from Spring AI.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class SolrVectorStoreIT {

    @Container
    private static final SolrContainer solrContainer = new SolrContainer(
            DockerImageName.parse("solr:9.7.0"));

    private static final String COLLECTION_NAME = "test_collection";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestApplication.class)
            .withPropertyValues(
                    "spring.ai.openai.api-key=" + System.getenv("OPENAI_API_KEY"),
                    "spring.ai.openai.embedding.options.model=text-embedding-3-small",
                    "solr.url=" + "http://" + solrContainer.getHost() + ":" + solrContainer.getSolrPort()
            );

    private List<Document> documents;

    @BeforeAll
    static void beforeAll() throws Exception {
        // Create collection once for all tests
        createSolrCollection();

        // Wait for collection to be fully ready
        Thread.sleep(3000);
    }

    private static void createSolrCollection() throws Exception {
        try {
            // Create the collection using exec in container
            var result = solrContainer.execInContainer(
                    "/opt/solr/bin/solr", "create_collection",
                    "-c", COLLECTION_NAME,
                    "-d", "_default",
                    "-shards", "1",
                    "-replicationFactor", "1"
            );

            System.out.println("Collection creation stdout: " + result.getStdout());
            if (result.getExitCode() != 0 && !result.getStderr().contains("already exists")) {
                System.err.println("Collection creation stderr: " + result.getStderr());
            }

            // Add the DenseVectorField to the schema for vector search
            // Using curl to add field via Schema API
            String schemaApiUrl = String.format("http://localhost:%d/solr/%s/schema",
                    solrContainer.getSolrPort(), COLLECTION_NAME);

            // First, add the field type for DenseVectorField
            // Note: Need to use proper JSON escaping for exec in container
            var typeResult = solrContainer.execInContainer(
                    "sh", "-c",
                    String.format(
                        "curl -X POST -H 'Content-Type: application/json' " +
                        "--data '{\"add-field-type\":{" +
                        "\"name\":\"knn_vector_1536\"," +
                        "\"class\":\"solr.DenseVectorField\"," +
                        "\"vectorDimension\":1536," +
                        "\"similarityFunction\":\"cosine\"," +
                        "\"knnAlgorithm\":\"hnsw\"}}' " +
                        "http://localhost:8983/solr/%s/schema", COLLECTION_NAME)
            );

            System.out.println("Schema field type addition: " + typeResult.getStdout());
            if (typeResult.getExitCode() != 0) {
                System.err.println("Field type error: " + typeResult.getStderr());
            }

            // Wait a bit for schema to update
            Thread.sleep(1000);

            // Then add the field using the field type we just created
            var fieldResult = solrContainer.execInContainer(
                    "sh", "-c",
                    String.format(
                        "curl -X POST -H 'Content-Type: application/json' " +
                        "--data '{\"add-field\":{" +
                        "\"name\":\"vector\"," +
                        "\"type\":\"knn_vector_1536\"," +
                        "\"stored\":true," +
                        "\"indexed\":true}}' " +
                        "http://localhost:8983/solr/%s/schema", COLLECTION_NAME)
            );

            System.out.println("Schema field addition: " + fieldResult.getStdout());
            if (fieldResult.getExitCode() != 0) {
                System.err.println("Field addition error: " + fieldResult.getStderr());
            }

        } catch (Exception e) {
            // Collection might already exist, which is fine
            System.out.println("Collection creation exception (might already exist): " + e.getMessage());
        }
    }

    @BeforeEach
    void setUp() {
        // Clean database before each test
        cleanDatabase();

        // Initialize test documents
        documents = List.of(
                new Document("1", "Spring AI provides abstractions for AI models", Map.of("category", "AI", "year", 2024)),
                new Document("2", "Vector databases store embeddings for similarity search", Map.of("category", "Database", "year", 2024)),
                new Document("3", "Apache Solr supports dense vector fields for KNN search", Map.of("category", "Search", "year", 2023))
        );
    }

    private void cleanDatabase() {
        contextRunner.run(context -> {
            SolrVectorStore vectorStore = context.getBean(SolrVectorStore.class);
            SolrClient solrClient = context.getBean(SolrClient.class);

            try {
                // Delete all documents
                solrClient.deleteByQuery(COLLECTION_NAME, "*:*");
                solrClient.commit(COLLECTION_NAME);
            } catch (Exception e) {
                // Ignore if collection doesn't exist yet
            }
        });
    }

    @Test
    void addAndDeleteDocumentsTest() {
        contextRunner.run(context -> {
            SolrVectorStore vectorStore = context.getBean(SolrVectorStore.class);
            SolrClient solrClient = context.getBean(SolrClient.class);

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
        });
    }

    @Test
    void addAndSearchTest() {
        contextRunner.run(context -> {
            SolrVectorStore vectorStore = context.getBean(SolrVectorStore.class);

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
        });
    }

    @Test
    void searchWithFiltersTest() {
        contextRunner.run(context -> {
            SolrVectorStore vectorStore = context.getBean(SolrVectorStore.class);

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
            // Note: Spring AI uses its own filter expression syntax, not Solr syntax
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
        });
    }

    @Test
    void documentUpdateTest() {
        contextRunner.run(context -> {
            SolrVectorStore vectorStore = context.getBean(SolrVectorStore.class);

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
        });
    }

    @Test
    void searchThresholdTest() {
        contextRunner.run(context -> {
            SolrVectorStore vectorStore = context.getBean(SolrVectorStore.class);

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
            double score1 = (double) ((Number) allResults.get(0).getMetadata().get("score")).floatValue();
            double score2 = (double) ((Number) allResults.get(1).getMetadata().get("score")).floatValue();

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
        });
    }

    @Test
    void overDefaultSizeTest() {
        contextRunner.run(context -> {
            SolrVectorStore vectorStore = context.getBean(SolrVectorStore.class);

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
        });
    }

    @Test
    void getNativeClientTest() {
        contextRunner.run(context -> {
            SolrVectorStore vectorStore = context.getBean(SolrVectorStore.class);

            // Get native client
            Optional<Object> nativeClient = vectorStore.getNativeClient();

            // Verify native client is available
            assertThat(nativeClient).isPresent();
            assertThat(nativeClient.get()).isInstanceOf(SolrClient.class);

            // Verify native client is functional
            SolrClient solrClient = (SolrClient) nativeClient.get();
            assertThat(solrClient).isNotNull();
        });
    }

    @SpringBootConfiguration
    static class TestApplication {

        @Bean
        public SolrClient solrClient() {
            // Base Solr URL without collection - collection is specified separately in VectorStore.builder()
            String solrUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getSolrPort() + "/solr";
            return new org.apache.solr.client.solrj.impl.HttpSolrClient.Builder(solrUrl).build();
        }

        @Bean
        public EmbeddingModel embeddingModel() {
            OpenAiApi openAiApi = OpenAiApi.builder()
                    .apiKey(System.getenv("OPENAI_API_KEY"))
                    .build();
            return new OpenAiEmbeddingModel(openAiApi);
        }

        @Bean
        public SolrVectorStore vectorStore(SolrClient solrClient, EmbeddingModel embeddingModel) {
            return SolrVectorStore.builder(solrClient, COLLECTION_NAME, embeddingModel)
                    .options(SolrVectorStoreOptions.defaults())
                    .build();
        }
    }
}
