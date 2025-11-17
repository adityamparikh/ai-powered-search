package dev.aparikh.aipoweredsearch.solr.vectorstore;

import io.micrometer.observation.ObservationRegistry;
import org.apache.solr.client.solrj.SolrClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for VectorStore bean.
 *
 * <p>This configuration creates a SolrVectorStore bean for the default collection.
 * The collection name can be configured via the application.properties file.</p>
 */
@Configuration
public class VectorStoreConfig {

    /**
     * Creates a VectorStore bean using SolrVectorStore implementation.
     *
     * @param solrClient the Solr client for connecting to Solr
     * @param embeddingModel the embedding model for generating vectors
     * @param collectionName the default collection name from properties
     * @return configured VectorStore instance
     */
    @Bean
    public VectorStore solrVectorStore(
            SolrClient solrClient,
            EmbeddingModel embeddingModel,
            @Value("${solr.default.collection:books}") String collectionName,
            ObservationRegistry observationRegistry) {
        return SolrVectorStore.builder(solrClient, collectionName, embeddingModel)
                .observationRegistry(observationRegistry)
                .build();
    }
}
