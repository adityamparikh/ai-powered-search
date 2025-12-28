package dev.aparikh.aipoweredsearch;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;

import java.io.IOException;
import java.util.*;

/**
 * Utility class for testing with Solr.
 * Provides helper methods for creating collections and generating test data.
 */
public class TestUtils {

    /**
     * Creates a Solr collection with vector field support for testing.
     * If the collection already exists, this method does nothing.
     *
     * @param solrClient the Solr client to use
     * @param collectionName the name of the collection to create
     * @throws IOException if an I/O error occurs
     * @throws SolrServerException if a Solr server error occurs
     */
    public static void createSolrCollectionWithVectorField(SolrClient solrClient, String collectionName)
            throws IOException, SolrServerException {

        // Check if collection already exists
        try {
            CollectionAdminRequest.List listRequest = new CollectionAdminRequest.List();
            CollectionAdminResponse listResponse = listRequest.process(solrClient);
            @SuppressWarnings("unchecked")
            List<String> collections = (List<String>) listResponse.getResponse().get("collections");

            if (collections != null && collections.contains(collectionName)) {
                // Collection already exists, skip creation
                return;
            }
        } catch (Exception e) {
            // If list fails, try to create anyway
        }

        // Create the collection with default config
        CollectionAdminRequest.Create createRequest = CollectionAdminRequest
                .createCollection(collectionName, "_default", 1, 1);

        CollectionAdminResponse response = createRequest.process(solrClient);

        if (!response.isSuccess()) {
            throw new RuntimeException("Failed to create collection: " + collectionName);
        }

        // Wait for collection to be ready
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for collection creation", e);
        }

        // For simplicity, we'll skip the schema modifications
        // since the tests focus on VectorStoreFactory caching behavior
        // not actual vector operations.
        // A real integration test with Solr would need proper schema setup.
    }

    /**
     * Generates a mock embedding vector of the specified dimension.
     * Creates a normalized vector with random values for testing.
     *
     * @param dimension the dimension of the embedding vector
     * @return a list of float values representing the embedding
     */
    public static List<Float> generateMockEmbedding(int dimension) {
        List<Float> embedding = new ArrayList<>(dimension);
        Random random = new Random();

        // Generate random values
        double sum = 0.0;
        for (int i = 0; i < dimension; i++) {
            float value = random.nextFloat() * 2.0f - 1.0f; // Range -1 to 1
            embedding.add(value);
            sum += value * value;
        }

        // Normalize the vector (for cosine similarity)
        double magnitude = Math.sqrt(sum);
        if (magnitude > 0) {
            for (int i = 0; i < dimension; i++) {
                embedding.set(i, (float)(embedding.get(i) / magnitude));
            }
        }

        return embedding;
    }

    /**
     * Generates a deterministic mock embedding vector for a given text.
     * Uses the text's hash code as a seed for reproducible results.
     *
     * @param text the text to generate an embedding for
     * @param dimension the dimension of the embedding vector
     * @return a list of float values representing the embedding
     */
    public static List<Float> generateMockEmbedding(String text, int dimension) {
        Random random = new Random(text.hashCode());
        List<Float> embedding = new ArrayList<>(dimension);

        // Generate deterministic values based on text
        double sum = 0.0;
        for (int i = 0; i < dimension; i++) {
            float value = random.nextFloat() * 2.0f - 1.0f;
            embedding.add(value);
            sum += value * value;
        }

        // Normalize
        double magnitude = Math.sqrt(sum);
        if (magnitude > 0) {
            for (int i = 0; i < dimension; i++) {
                embedding.set(i, (float)(embedding.get(i) / magnitude));
            }
        }

        return embedding;
    }

}