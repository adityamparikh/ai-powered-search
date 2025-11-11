package dev.aparikh.aipoweredsearch.solr.vectorstore;

import org.apache.solr.client.solrj.SolrClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Factory for creating and caching VectorStore instances per Solr collection.
 *
 * <p>This factory maintains a bounded LRU (Least Recently Used) cache to prevent
 * unbounded memory growth while still providing efficient reuse of VectorStore
 * instances. The cache is thread-safe and will automatically evict the least
 * recently used entries when the maximum size is reached.</p>
 *
 * <p>The default cache size is 100 collections, which should be sufficient for
 * most use cases while preventing memory issues in systems with many collections.</p>
 */
@Component
public class VectorStoreFactory {

    /**
     * Maximum number of VectorStore instances to cache.
     * When this limit is reached, the least recently used instance will be evicted.
     */
    private static final int MAX_CACHE_SIZE = 100;

    private final SolrClient solrClient;
    private final EmbeddingModel embeddingModel;

    /**
     * Thread-safe LRU cache for VectorStore instances.
     * Uses a synchronized LinkedHashMap with access-order to implement LRU eviction.
     */
    private final Map<String, VectorStore> cache;

    public VectorStoreFactory(SolrClient solrClient, EmbeddingModel embeddingModel) {
        this.solrClient = solrClient;
        this.embeddingModel = embeddingModel;

        // Create a synchronized LRU cache using LinkedHashMap
        this.cache = Collections.synchronizedMap(
            new LinkedHashMap<String, VectorStore>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, VectorStore> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            }
        );
    }

    /**
     * Returns a VectorStore instance bound to the given collection.
     * Instances are cached for reuse with LRU eviction policy.
     *
     * @param collection the name of the Solr collection
     * @return a VectorStore instance for the specified collection
     * @throws NullPointerException if collection is null
     */
    public VectorStore forCollection(String collection) {
        if (collection == null) {
            throw new NullPointerException("Collection name cannot be null");
        }

        // computeIfAbsent is atomic for ConcurrentHashMap but we're using synchronized map
        // So we need to handle this carefully to avoid creating multiple instances
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
}
