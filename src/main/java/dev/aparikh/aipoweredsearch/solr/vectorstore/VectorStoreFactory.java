package dev.aparikh.aipoweredsearch.solr.vectorstore;

import org.apache.solr.client.solrj.SolrClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating and caching VectorStore instances per Solr collection.
 */
@Component
public class VectorStoreFactory {

    private final SolrClient solrClient;
    private final EmbeddingModel embeddingModel;

    private final Map<String, VectorStore> cache = new ConcurrentHashMap<>();

    public VectorStoreFactory(SolrClient solrClient, EmbeddingModel embeddingModel) {
        this.solrClient = solrClient;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Returns a VectorStore instance bound to the given collection.
     * Instances are cached for reuse.
     */
    public VectorStore forCollection(String collection) {
        return cache.computeIfAbsent(collection, c ->
                SolrVectorStore.builder(solrClient, c, embeddingModel)
                        .build()
        );
    }
}
