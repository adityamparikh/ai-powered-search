package dev.aparikh.aipoweredsearch.solr.vectorstore;

import dev.aparikh.aipoweredsearch.TestUtils;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.junit.jupiter.api.*;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration tests for VectorStoreFactory using Solr Testcontainers.
 * Tests caching behavior, concurrent access, and proper VectorStore creation.
 */
@SpringBootTest(classes = {VectorStoreFactoryIT.TestConfig.class},
    properties = "spring.main.web-application-type=none")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VectorStoreFactoryIT {

    @Container
    private static final SolrContainer solrContainer = new SolrContainer(
            DockerImageName.parse("solr:slim"))
            .withEnv("SOLR_HEAP", "512m");

    @Autowired
    private VectorStoreFactory vectorStoreFactory;

    @Autowired
    private SolrClient solrClient;

    @MockBean
    private EmbeddingModel embeddingModel;

    private static final String TEST_COLLECTION_1 = "test_collection_1";
    private static final String TEST_COLLECTION_2 = "test_collection_2";
    private static final String TEST_COLLECTION_3 = "test_collection_3";

    @DynamicPropertySource
    static void solrProperties(DynamicPropertyRegistry registry) {
        registry.add("solr.url", () ->
            "http://" + solrContainer.getHost() + ":" + solrContainer.getSolrPort() + "/solr");
    }

    @BeforeAll
    static void beforeAll() throws Exception {
        // Create test collections with vector field support
        String solrUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getSolrPort() + "/solr";

        try (Http2SolrClient adminClient = new Http2SolrClient.Builder(solrUrl).build()) {
            // Create collections for testing
            TestUtils.createSolrCollectionWithVectorField(adminClient, TEST_COLLECTION_1);
            TestUtils.createSolrCollectionWithVectorField(adminClient, TEST_COLLECTION_2);
            TestUtils.createSolrCollectionWithVectorField(adminClient, TEST_COLLECTION_3);
        }
    }

    @BeforeEach
    void setUp() {
        // Clear cache before each test
        vectorStoreFactory.clearCache();

        // Mock embedding model to return test embeddings
        List<Float> mockEmbedding = TestUtils.generateMockEmbedding(1536);
        float[] embeddingArray = new float[mockEmbedding.size()];
        for (int i = 0; i < mockEmbedding.size(); i++) {
            embeddingArray[i] = mockEmbedding.get(i);
        }
        when(embeddingModel.embed(any(String.class)))
                .thenReturn(embeddingArray);
    }

    @Test
    @Order(1)
    @DisplayName("Should create and cache VectorStore instances")
    void shouldCreateAndCacheVectorStores() {
        // Given
        assertThat(vectorStoreFactory.getCacheSize()).isZero();

        // When - create first VectorStore
        VectorStore store1 = vectorStoreFactory.forCollection(TEST_COLLECTION_1);

        // Then
        assertThat(store1).isNotNull();
        assertThat(store1).isInstanceOf(SolrVectorStore.class);
        assertThat(vectorStoreFactory.getCacheSize()).isEqualTo(1);

        // When - request same collection again
        VectorStore store1Again = vectorStoreFactory.forCollection(TEST_COLLECTION_1);

        // Then - should return cached instance
        assertThat(store1Again).isSameAs(store1);
        assertThat(vectorStoreFactory.getCacheSize()).isEqualTo(1);

        // When - create second VectorStore for different collection
        VectorStore store2 = vectorStoreFactory.forCollection(TEST_COLLECTION_2);

        // Then
        assertThat(store2).isNotNull();
        assertThat(store2).isInstanceOf(SolrVectorStore.class);
        assertThat(store2).isNotSameAs(store1);
        assertThat(vectorStoreFactory.getCacheSize()).isEqualTo(2);
    }

    @Test
    @Order(2)
    @DisplayName("Should handle concurrent access correctly")
    void shouldHandleConcurrentAccess() throws InterruptedException, ExecutionException {
        // Given
        int numberOfThreads = 10;
        int requestsPerThread = 5;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);

        // When - multiple threads request same collections concurrently
        List<Future<VectorStore>> futures = new ArrayList<>();

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadId = i;
            Future<VectorStore> future = executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    // Each thread requests collection based on its ID mod 3
                    String collection = switch (threadId % 3) {
                        case 0 -> TEST_COLLECTION_1;
                        case 1 -> TEST_COLLECTION_2;
                        default -> TEST_COLLECTION_3;
                    };
                    return vectorStoreFactory.forCollection(collection);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
            futures.add(future);
        }

        // Start all threads simultaneously
        startLatch.countDown();

        // Collect results
        List<VectorStore> stores = new ArrayList<>();
        for (Future<VectorStore> future : futures) {
            stores.add(future.get());
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Then - should have exactly 3 unique instances (one per collection)
        assertThat(vectorStoreFactory.getCacheSize()).isEqualTo(3);

        // Verify all threads got the same instance for the same collection
        Set<VectorStore> uniqueStores = stores.stream().collect(Collectors.toSet());
        assertThat(uniqueStores).hasSize(3);

        // Verify cache consistency
        VectorStore cachedStore1 = vectorStoreFactory.forCollection(TEST_COLLECTION_1);
        VectorStore cachedStore2 = vectorStoreFactory.forCollection(TEST_COLLECTION_2);
        VectorStore cachedStore3 = vectorStoreFactory.forCollection(TEST_COLLECTION_3);

        assertThat(stores.stream().filter(s -> s == cachedStore1).count())
                .isEqualTo(numberOfThreads / 3 + (numberOfThreads % 3 > 0 ? 1 : 0));
        assertThat(stores.stream().filter(s -> s == cachedStore2).count())
                .isEqualTo(numberOfThreads / 3 + (numberOfThreads % 3 > 1 ? 1 : 0));
        assertThat(stores.stream().filter(s -> s == cachedStore3).count())
                .isEqualTo(numberOfThreads / 3);
    }

    @Test
    @Order(3)
    @DisplayName("Should handle cache operations correctly")
    void shouldHandleCacheOperations() {
        // Given - populate cache
        VectorStore store1 = vectorStoreFactory.forCollection(TEST_COLLECTION_1);
        VectorStore store2 = vectorStoreFactory.forCollection(TEST_COLLECTION_2);
        assertThat(vectorStoreFactory.getCacheSize()).isEqualTo(2);

        // When - evict specific collection
        VectorStore evicted = vectorStoreFactory.evict(TEST_COLLECTION_1);

        // Then
        assertThat(evicted).isSameAs(store1);
        assertThat(vectorStoreFactory.getCacheSize()).isEqualTo(1);

        // When - request evicted collection again
        VectorStore newStore1 = vectorStoreFactory.forCollection(TEST_COLLECTION_1);

        // Then - should create new instance
        assertThat(newStore1).isNotSameAs(store1);
        assertThat(vectorStoreFactory.getCacheSize()).isEqualTo(2);

        // When - clear entire cache
        vectorStoreFactory.clearCache();

        // Then
        assertThat(vectorStoreFactory.getCacheSize()).isZero();

        // When - request collections again
        VectorStore newStore1Again = vectorStoreFactory.forCollection(TEST_COLLECTION_1);
        VectorStore newStore2 = vectorStoreFactory.forCollection(TEST_COLLECTION_2);

        // Then - should create new instances
        assertThat(newStore1Again).isNotSameAs(store1);
        assertThat(newStore2).isNotSameAs(store2);
        assertThat(vectorStoreFactory.getCacheSize()).isEqualTo(2);
    }

    @Test
    @Order(4)
    @DisplayName("Should validate collection name")
    void shouldValidateCollectionName() {
        // Test null collection name
        assertThatThrownBy(() -> vectorStoreFactory.forCollection(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Collection name cannot be null or blank");

        // Test empty collection name
        assertThatThrownBy(() -> vectorStoreFactory.forCollection(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Collection name cannot be null or blank");

        // Test blank collection name
        assertThatThrownBy(() -> vectorStoreFactory.forCollection("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Collection name cannot be null or blank");
    }

    @Test
    @Order(5)
    @DisplayName("Should handle rapid cache operations under load")
    void shouldHandleRapidCacheOperations() throws InterruptedException {
        // Given
        int operations = 100;
        ExecutorService executor = Executors.newCachedThreadPool();
        CountDownLatch completionLatch = new CountDownLatch(operations);

        // When - perform random cache operations concurrently
        IntStream.range(0, operations).forEach(i -> {
            executor.submit(() -> {
                try {
                    int operation = i % 4;
                    switch (operation) {
                        case 0 -> vectorStoreFactory.forCollection(TEST_COLLECTION_1);
                        case 1 -> vectorStoreFactory.forCollection(TEST_COLLECTION_2);
                        case 2 -> vectorStoreFactory.evict(TEST_COLLECTION_1);
                        case 3 -> {
                            if (i % 10 == 0) { // Clear cache occasionally
                                vectorStoreFactory.clearCache();
                            } else {
                                vectorStoreFactory.forCollection(TEST_COLLECTION_3);
                            }
                        }
                    }
                } finally {
                    completionLatch.countDown();
                }
            });
        });

        // Wait for completion
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Then - cache should be in consistent state
        assertThat(vectorStoreFactory.getCacheSize()).isLessThanOrEqualTo(3);

        // Verify we can still get stores without errors
        VectorStore store1 = vectorStoreFactory.forCollection(TEST_COLLECTION_1);
        VectorStore store2 = vectorStoreFactory.forCollection(TEST_COLLECTION_2);
        VectorStore store3 = vectorStoreFactory.forCollection(TEST_COLLECTION_3);

        assertThat(store1).isNotNull();
        assertThat(store2).isNotNull();
        assertThat(store3).isNotNull();
    }

    @Test
    @Order(6)
    @DisplayName("Should maintain separate VectorStore instances for different collections")
    void shouldMaintainSeparateInstances() {
        // Given - create stores for different collections
        VectorStore store1 = vectorStoreFactory.forCollection(TEST_COLLECTION_1);
        VectorStore store2 = vectorStoreFactory.forCollection(TEST_COLLECTION_2);
        VectorStore store3 = vectorStoreFactory.forCollection(TEST_COLLECTION_3);

        // Then - all should be different instances
        assertThat(store1).isNotSameAs(store2);
        assertThat(store2).isNotSameAs(store3);
        assertThat(store1).isNotSameAs(store3);

        // And - cache size should reflect all three
        assertThat(vectorStoreFactory.getCacheSize()).isEqualTo(3);

        // When - request same collections again
        VectorStore store1Again = vectorStoreFactory.forCollection(TEST_COLLECTION_1);
        VectorStore store2Again = vectorStoreFactory.forCollection(TEST_COLLECTION_2);
        VectorStore store3Again = vectorStoreFactory.forCollection(TEST_COLLECTION_3);

        // Then - should get same instances from cache
        assertThat(store1Again).isSameAs(store1);
        assertThat(store2Again).isSameAs(store2);
        assertThat(store3Again).isSameAs(store3);
        assertThat(vectorStoreFactory.getCacheSize()).isEqualTo(3);
    }

    @TestConfiguration
    @Import({VectorStoreFactory.class})
    static class TestConfig {

        @Bean
        @Primary
        public SolrClient solrClient() {
            String solrUrl = "http://" + solrContainer.getHost() + ":" +
                           solrContainer.getSolrPort() + "/solr";
            return new Http2SolrClient.Builder(solrUrl).build();
        }
    }
}