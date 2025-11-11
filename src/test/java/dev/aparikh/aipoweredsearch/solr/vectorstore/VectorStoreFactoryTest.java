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
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testEmptyCollectionName() {
        // Given
        String emptyCollection = "";

        // When
        VectorStore vectorStore = factory.forCollection(emptyCollection);

        // Then
        assertThat(vectorStore).isNotNull();
        // Empty string is technically valid, but should be validated at a higher level
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
}