package dev.aparikh.aipoweredsearch.solr.vectorstore;

import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for VectorStoreFactory
 */
@ExtendWith(MockitoExtension.class)
class VectorStoreFactoryTest {

    @Mock
    private SolrClient solrClient;

    @Mock
    private EmbeddingModel embeddingModel;

    private VectorStoreFactory factory;

    @BeforeEach
    void setUp() {
        factory = new VectorStoreFactory(solrClient, embeddingModel);
    }

    @Test
    void testCreateVectorStore() {
        // Given
        String collection = "testCollection";

        // When
        VectorStore vectorStore = factory.forCollection(collection);

        // Then
        assertThat(vectorStore).isNotNull();
        assertThat(vectorStore).isInstanceOf(SolrVectorStore.class);
    }

    @Test
    void testCacheReturnsSameInstance() {
        // Given
        String collection = "testCollection";

        // When
        VectorStore store1 = factory.forCollection(collection);
        VectorStore store2 = factory.forCollection(collection);

        // Then
        assertThat(store1).isSameAs(store2);
    }

    @Test
    void testDifferentCollectionsGetDifferentInstances() {
        // Given
        String collection1 = "collection1";
        String collection2 = "collection2";

        // When
        VectorStore store1 = factory.forCollection(collection1);
        VectorStore store2 = factory.forCollection(collection2);

        // Then
        assertThat(store1).isNotSameAs(store2);
    }

    @Test
    void testNullCollectionHandling() {
        // When & Then
        assertThatThrownBy(() -> factory.forCollection(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Collection name cannot be null or blank");
    }

    @Test
    void testThreadSafety() throws InterruptedException {
        // Given
        int threadCount = 10;
        int iterationsPerThread = 100;
        String collection = "testCollection";

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger instanceCount = new AtomicInteger(0);
        VectorStore[] firstInstance = new VectorStore[1];

        // When
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready

                    for (int j = 0; j < iterationsPerThread; j++) {
                        VectorStore store = factory.forCollection(collection);

                        // Capture the first instance created
                        if (firstInstance[0] == null) {
                            synchronized (firstInstance) {
                                if (firstInstance[0] == null) {
                                    firstInstance[0] = store;
                                    instanceCount.incrementAndGet();
                                }
                            }
                        }

                        // Verify all threads get the same instance
                        assertThat(store).isSameAs(firstInstance[0]);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        startLatch.countDown();

        // Wait for all threads to complete
        boolean completed = endLatch.await(10, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // Verify only one instance was created despite concurrent access
        assertThat(instanceCount.get()).isEqualTo(1);

        executor.shutdown();
    }

    @Test
    void testMultipleCollectionsConcurrent() throws InterruptedException {
        // Given
        int threadCount = 10;
        String[] collections = {"col1", "col2", "col3", "col4", "col5"};

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        // When
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready

                    // Each thread accesses different collections
                    String collection = collections[threadId % collections.length];
                    VectorStore store1 = factory.forCollection(collection);
                    VectorStore store2 = factory.forCollection(collection);

                    // Verify cache works even under concurrent access
                    assertThat(store1).isSameAs(store2);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        startLatch.countDown();

        // Wait for all threads to complete
        boolean completed = endLatch.await(10, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        executor.shutdown();
    }

    @Test
    void testSpecialCharactersInCollectionName() {
        // Given - collection names with special characters that might be valid in Solr
        String[] specialCollections = {
            "test-collection",
            "test_collection",
            "test.collection",
            "test123",
            "TEST_COLLECTION"
        };

        // When & Then
        for (String collection : specialCollections) {
            VectorStore store = factory.forCollection(collection);
            assertThat(store).isNotNull();

            // Verify caching works with special characters
            VectorStore cachedStore = factory.forCollection(collection);
            assertThat(cachedStore).isSameAs(store);
        }
    }

    @Test
    void testClearCache() {
        // Given - populate cache with multiple collections
        String collection1 = "collection1";
        String collection2 = "collection2";
        String collection3 = "collection3";

        VectorStore store1 = factory.forCollection(collection1);
        VectorStore store2 = factory.forCollection(collection2);
        VectorStore store3 = factory.forCollection(collection3);

        assertThat(factory.getCacheSize()).isEqualTo(3);

        // When - clear the cache
        factory.clearCache();

        // Then - cache should be empty
        assertThat(factory.getCacheSize()).isEqualTo(0);

        // And - new instances should be created after clearing
        VectorStore newStore1 = factory.forCollection(collection1);
        assertThat(newStore1).isNotNull();
        assertThat(newStore1).isNotSameAs(store1); // Different instance after clear
        assertThat(factory.getCacheSize()).isEqualTo(1);
    }

    @Test
    void testEvictSpecificCollection() {
        // Given - populate cache with multiple collections
        String collection1 = "collection1";
        String collection2 = "collection2";
        String collection3 = "collection3";

        VectorStore store1 = factory.forCollection(collection1);
        VectorStore store2 = factory.forCollection(collection2);
        VectorStore store3 = factory.forCollection(collection3);

        assertThat(factory.getCacheSize()).isEqualTo(3);

        // When - evict a specific collection
        VectorStore evictedStore = factory.evict(collection2);

        // Then
        assertThat(evictedStore).isSameAs(store2); // Returns the evicted instance
        assertThat(factory.getCacheSize()).isEqualTo(2); // Cache size reduced

        // The other collections should still be cached
        assertThat(factory.forCollection(collection1)).isSameAs(store1);
        assertThat(factory.forCollection(collection3)).isSameAs(store3);

        // A new instance should be created for the evicted collection
        VectorStore newStore2 = factory.forCollection(collection2);
        assertThat(newStore2).isNotNull();
        assertThat(newStore2).isNotSameAs(store2); // Different instance after eviction
    }

    @Test
    void testEvictNonExistentCollection() {
        // Given
        String existingCollection = "existing";
        String nonExistentCollection = "nonexistent";

        factory.forCollection(existingCollection);
        assertThat(factory.getCacheSize()).isEqualTo(1);

        // When - evict a collection that doesn't exist
        VectorStore result = factory.evict(nonExistentCollection);

        // Then
        assertThat(result).isNull(); // Should return null for non-existent
        assertThat(factory.getCacheSize()).isEqualTo(1); // Cache size unchanged
    }

    @Test
    void testGetCacheSize() {
        // Given - empty cache
        assertThat(factory.getCacheSize()).isEqualTo(0);

        // When - add collections one by one
        factory.forCollection("col1");
        assertThat(factory.getCacheSize()).isEqualTo(1);

        factory.forCollection("col2");
        assertThat(factory.getCacheSize()).isEqualTo(2);

        factory.forCollection("col3");
        assertThat(factory.getCacheSize()).isEqualTo(3);

        // Access existing collection shouldn't change size
        factory.forCollection("col1");
        assertThat(factory.getCacheSize()).isEqualTo(3);

        // After eviction
        factory.evict("col2");
        assertThat(factory.getCacheSize()).isEqualTo(2);

        // After clear
        factory.clearCache();
        assertThat(factory.getCacheSize()).isEqualTo(0);
    }

    @Test
    void testBlankCollectionName() {
        // When & Then - blank collection names should throw exception
        assertThatThrownBy(() -> factory.forCollection(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Collection name cannot be null or blank");

        assertThatThrownBy(() -> factory.forCollection("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Collection name cannot be null or blank");
    }
}