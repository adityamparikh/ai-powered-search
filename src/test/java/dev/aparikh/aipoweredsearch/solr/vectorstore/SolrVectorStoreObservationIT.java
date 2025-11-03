package dev.aparikh.aipoweredsearch.solr.vectorstore;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
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
import org.springframework.ai.vectorstore.observation.DefaultVectorStoreObservationConvention;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationDocumentation;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for SolrVectorStore observability.
 * Based on ElasticsearchVectorStoreObservationIT from Spring AI.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class SolrVectorStoreObservationIT {

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
                new Document("1", "Spring AI provides abstractions for AI models", Map.of("year", 2024)),
                new Document("2", "Vector databases store embeddings", Map.of("year", 2024)),
                new Document("3", "Apache Solr supports KNN search", Map.of("year", 2023))
        );
    }

    private void cleanDatabase() {
        contextRunner.run(context -> {
            SolrClient solrClient = context.getBean(SolrClient.class);
            try {
                solrClient.deleteByQuery(COLLECTION_NAME, "*:*");
                solrClient.commit(COLLECTION_NAME);
            } catch (Exception e) {
                // Ignore if collection doesn't exist yet
            }
        });
    }

    @Test
    void observationVectorStoreAddAndQueryOperations() {
        contextRunner.run(context -> {
            SolrVectorStore vectorStore = context.getBean(SolrVectorStore.class);
            TestObservationRegistry observationRegistry = context.getBean(TestObservationRegistry.class);

            // Clear any previous observations
            observationRegistry.clear();

            // Perform add operation
            vectorStore.add(documents);

            // Wait for indexing
            await().untilAsserted(() -> {
                List<Document> results = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query("AI")
                                .topK(1)
                                .build()
                );
                assertThat(results).isNotEmpty();
            });

            // Clear observations from wait assertions
            observationRegistry.clear();

            // Perform query operation
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("What is Spring AI")
                            .topK(1)
                            .similarityThreshold(0.0)
                            .build()
            );

            assertThat(results).isNotEmpty();

            // Verify observations
            TestObservationRegistryAssert.assertThat(observationRegistry)
                    .doesNotHaveAnyRemainingCurrentObservation()
                    .hasObservationWithNameEqualTo(DefaultVectorStoreObservationConvention.DEFAULT_NAME)
                    .that()
                    .hasContextualNameEqualTo("solr query");

            // Verify low cardinality keys
            TestObservationRegistryAssert.assertThat(observationRegistry)
                    .hasObservationWithNameEqualTo(DefaultVectorStoreObservationConvention.DEFAULT_NAME)
                    .that()
                    .hasLowCardinalityKeyValue(
                            VectorStoreObservationDocumentation.LowCardinalityKeyNames.DB_SYSTEM.asString(),
                            "solr"
                    )
                    .hasLowCardinalityKeyValue(
                            VectorStoreObservationDocumentation.LowCardinalityKeyNames.DB_OPERATION_NAME.asString(),
                            "query"
                    )
                    .hasLowCardinalityKeyValue(
                            VectorStoreObservationDocumentation.LowCardinalityKeyNames.SPRING_AI_KIND.asString(),
                            "vector_store"
                    );

            // Verify high cardinality keys
            TestObservationRegistryAssert.assertThat(observationRegistry)
                    .hasObservationWithNameEqualTo(DefaultVectorStoreObservationConvention.DEFAULT_NAME)
                    .that()
                    .hasHighCardinalityKeyValue(
                            VectorStoreObservationDocumentation.HighCardinalityKeyNames.DB_VECTOR_QUERY_CONTENT.asString(),
                            "What is Spring AI"
                    )
                    .hasHighCardinalityKeyValue(
                            VectorStoreObservationDocumentation.HighCardinalityKeyNames.DB_VECTOR_DIMENSION_COUNT.asString(),
                            "1536"
                    )
                    .hasHighCardinalityKeyValue(
                            VectorStoreObservationDocumentation.HighCardinalityKeyNames.DB_COLLECTION_NAME.asString(),
                            COLLECTION_NAME
                    )
                    .hasHighCardinalityKeyValue(
                            VectorStoreObservationDocumentation.HighCardinalityKeyNames.DB_VECTOR_QUERY_TOP_K.asString(),
                            "1"
                    )
                    .hasHighCardinalityKeyValue(
                            VectorStoreObservationDocumentation.HighCardinalityKeyNames.DB_VECTOR_QUERY_SIMILARITY_THRESHOLD.asString(),
                            "0.0"
                    );

            // Verify observation was started and stopped
            TestObservationRegistryAssert.assertThat(observationRegistry)
                    .hasObservationWithNameEqualTo(DefaultVectorStoreObservationConvention.DEFAULT_NAME)
                    .that()
                    .hasBeenStarted()
                    .hasBeenStopped();
        });
    }

    @Test
    void observationVectorStoreAddOperation() {
        contextRunner.run(context -> {
            SolrVectorStore vectorStore = context.getBean(SolrVectorStore.class);
            TestObservationRegistry observationRegistry = context.getBean(TestObservationRegistry.class);

            // Clear any previous observations
            observationRegistry.clear();

            // Perform add operation
            vectorStore.add(documents);

            // Verify add observation
            TestObservationRegistryAssert.assertThat(observationRegistry)
                    .doesNotHaveAnyRemainingCurrentObservation()
                    .hasObservationWithNameEqualTo(DefaultVectorStoreObservationConvention.DEFAULT_NAME)
                    .that()
                    .hasContextualNameEqualTo("solr add");

            // Verify low cardinality keys for add operation
            TestObservationRegistryAssert.assertThat(observationRegistry)
                    .hasObservationWithNameEqualTo(DefaultVectorStoreObservationConvention.DEFAULT_NAME)
                    .that()
                    .hasLowCardinalityKeyValue(
                            VectorStoreObservationDocumentation.LowCardinalityKeyNames.DB_SYSTEM.asString(),
                            "solr"
                    )
                    .hasLowCardinalityKeyValue(
                            VectorStoreObservationDocumentation.LowCardinalityKeyNames.DB_OPERATION_NAME.asString(),
                            "add"
                    )
                    .hasLowCardinalityKeyValue(
                            VectorStoreObservationDocumentation.LowCardinalityKeyNames.SPRING_AI_KIND.asString(),
                            "vector_store"
                    );

            // Verify high cardinality keys for add operation
            TestObservationRegistryAssert.assertThat(observationRegistry)
                    .hasObservationWithNameEqualTo(DefaultVectorStoreObservationConvention.DEFAULT_NAME)
                    .that()
                    .hasHighCardinalityKeyValue(
                            VectorStoreObservationDocumentation.HighCardinalityKeyNames.DB_VECTOR_DIMENSION_COUNT.asString(),
                            "1536"
                    )
                    .hasHighCardinalityKeyValue(
                            VectorStoreObservationDocumentation.HighCardinalityKeyNames.DB_COLLECTION_NAME.asString(),
                            COLLECTION_NAME
                    );

            // Verify observation lifecycle
            TestObservationRegistryAssert.assertThat(observationRegistry)
                    .hasObservationWithNameEqualTo(DefaultVectorStoreObservationConvention.DEFAULT_NAME)
                    .that()
                    .hasBeenStarted()
                    .hasBeenStopped();
        });
    }

    @SpringBootConfiguration
    static class TestApplication {

        @Bean
        public TestObservationRegistry observationRegistry() {
            return TestObservationRegistry.create();
        }

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
        public SolrVectorStore vectorStore(SolrClient solrClient, EmbeddingModel embeddingModel,
                                            ObservationRegistry observationRegistry) {
            return SolrVectorStore.builder(solrClient, COLLECTION_NAME, embeddingModel)
                    .options(SolrVectorStoreOptions.defaults())
                    .observationRegistry(observationRegistry)
                    .build();
        }
    }
}
