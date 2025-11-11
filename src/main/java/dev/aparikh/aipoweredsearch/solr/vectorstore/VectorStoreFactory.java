package dev.aparikh.aipoweredsearch.solr.vectorstore;

import org.apache.solr.client.solrj.SolrClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating and caching VectorStore instances per Solr collection.
 *
 * <p>This factory maintains a simple cache using {@link ConcurrentHashMap} for
 * high-performance concurrent access. While this implementation doesn't provide
 * LRU eviction, it offers excellent concurrency characteristics and is suitable
 * for most use cases where the number of collections is reasonably bounded.</p>
 *
 * <p>For production systems with many collections (>100), consider implementing
 * LRU eviction using a library like Caffeine or Guava Cache, or manually tracking
 * access patterns if strict memory bounds are required.</p>
 *
 * <p>Benefits of using ConcurrentHashMap:</p>
 * <ul>
 *   <li>Lock-free reads and fine-grained locking for writes</li>
 *   <li>Better performance under high concurrency</li>
 *   <li>No global locks that could become bottlenecks</li>
 *   <li>Simpler implementation with fewer moving parts</li>
 * </ul>
 */
@Component
public class VectorStoreFactory {

    /**
     * Suggested maximum cache size for monitoring purposes.
     * This is not enforced but can be used for alerting.
     */
    private static final int SUGGESTED_MAX_SIZE = 100;

    private final SolrClient solrClient;
    private final EmbeddingModel embeddingModel;

    /**
     * High-performance concurrent cache for VectorStore instances.
     * Uses ConcurrentHashMap for excellent concurrency characteristics.
     */
    private final Map<String, VectorStore> cache;

    public VectorStoreFactory(SolrClient solrClient, EmbeddingModel embeddingModel) {
        this.solrClient = solrClient;
        this.embeddingModel = embeddingModel;
        this.cache = new ConcurrentHashMap<>();
    }

    /**
     * Returns a VectorStore instance bound to the given collection.
     * Instances are cached for reuse.
     *
     * <p>This method uses {@link ConcurrentHashMap#computeIfAbsent} which provides
     * atomic creation of VectorStore instances, ensuring that only one instance
     * is created per collection even under concurrent access.</p>
     *
     * @param collection the name of the Solr collection
     * @return a VectorStore instance for the specified collection
     * @throws NullPointerException if collection is null
     */
    public VectorStore forCollection(String collection) {
        if (collection == null) {
            throw new NullPointerException("Collection name cannot be null");
        }

        // ConcurrentHashMap.computeIfAbsent is atomic and efficient
        // It guarantees that the mapping function is called at most once
        return cache.computeIfAbsent(collection, c ->
                SolrVectorStore.builder(solrClient, c, embeddingModel)
                        .build()
        );
    }

    /**
     * Returns the current size of the cache.
     * Useful for monitoring and testing.
     *
     * @return the number of cached VectorStore instances
     */
    public int getCacheSize() {
        return cache.size();
    }

    /**
     * Clears the cache, removing all cached VectorStore instances.
     * Useful for testing or when you need to force recreation of instances.
     */
    public void clearCache() {
        cache.clear();
    }

    /**
     * Removes a specific collection from the cache.
     * Useful when a collection is deleted or needs to be refreshed.
     *
     * @param collection the name of the collection to remove
     * @return the removed VectorStore instance, or null if not cached
     */
    public VectorStore evict(String collection) {
        return cache.remove(collection);
    }
}
