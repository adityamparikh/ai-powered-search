package dev.aparikh.aipoweredsearch.solr.vectorstore;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.RestClientConfig;
import dev.aparikh.aipoweredsearch.config.SolrConfig;
import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
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
import org.springframework.ai.vectorstore.observation.DefaultVectorStoreObservationConvention;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationDocumentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solr.SolrContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.ai.openai.embedding.options.model=text-embedding-3-small"
        })
@Testcontainers
@Import({PostgresTestConfiguration.class, RestClientConfig.class, SolrConfig.class, SolrTestConfiguration.class, SolrVectorStoreObservationIT.TestConfig.class})
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class SolrVectorStoreObservationIT {

    private static final String COLLECTION_NAME = "test_collection";

    @Autowired
    SolrContainer solrContainer;

    @Autowired
    private SolrClient solrClient;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private TestObservationRegistry observationRegistry;

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry registry) {
        registry.add("solr.default.collection", () -> COLLECTION_NAME);
    }

    private List<Document> documents;

    @BeforeEach
    void setUp() {
        // Create collection and schema idempotently
        try {
            solrContainer.execInContainer("/opt/solr/bin/solr", "create_collection", "-c", COLLECTION_NAME, "-d", "_default");
        } catch (Exception ignored) {
        }
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

        try {
            solrClient.deleteByQuery(COLLECTION_NAME, "*:*");
            solrClient.commit(COLLECTION_NAME);
        } catch (Exception ignored) {
        }

        // Initialize test documents
        documents = List.of(
                new Document("1", "Spring AI provides abstractions for AI models", Map.of("year", 2024)),
                new Document("2", "Vector databases store embeddings", Map.of("year", 2024)),
                new Document("3", "Apache Solr supports KNN search", Map.of("year", 2023))
        );
    }

    @Test
    void observationVectorStoreAddAndQueryOperations() {
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
    }

    @Test
    void observationVectorStoreAddOperation() {
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
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        TestObservationRegistry testObservationRegistry() {
            return TestObservationRegistry.create();
        }

        @Bean
        @Primary
        ObservationRegistry observationRegistry(TestObservationRegistry testObservationRegistry) {
            return testObservationRegistry;
        }

        @Bean
        EmbeddingModel embeddingModel(org.springframework.web.client.RestClient.Builder restClientBuilder) {
            var api = OpenAiApi.builder()
                    .apiKey(System.getenv("OPENAI_API_KEY"))
                    .restClientBuilder(restClientBuilder)
                    .build();
            return new OpenAiEmbeddingModel(api);
        }

        @Bean
        @Primary
        VectorStore vectorStore(SolrClient solrClient, EmbeddingModel embeddingModel, ObservationRegistry observationRegistry) {
            // Build a SolrVectorStore with our observationRegistry to capture metrics in tests
            return SolrVectorStore.builder(solrClient, COLLECTION_NAME, embeddingModel)
                    .observationRegistry(observationRegistry)
                    .options(SolrVectorStoreOptions.defaults())
                    .build();
        }
    }
}
