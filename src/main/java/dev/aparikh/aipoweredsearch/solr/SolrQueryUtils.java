package dev.aparikh.aipoweredsearch.solr;

/**
 * Utility class for building Apache Solr queries.
 *
 * <p>Provides static methods for constructing common Solr query patterns,
 * particularly for vector similarity search using KNN (K-Nearest Neighbors).</p>
 *
 * @author Aditya Parikh
 * @since 1.0.0
 */
public final class SolrQueryUtils {

    /**
     * Default vector field name used in Solr collections.
     */
    public static final String DEFAULT_VECTOR_FIELD = "vector";

    private SolrQueryUtils() {
        // Private constructor to prevent instantiation
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Builds a KNN (K-Nearest Neighbors) query string for Solr vector search.
     *
     * <p>Constructs a query in the format: {@code {!knn f=vector topK=10}[0.1, 0.2, 0.3, ...]}</p>
     *
     * <p>This query uses Solr's KNN query parser to find the topK most similar documents
     * based on vector similarity (typically cosine similarity).</p>
     *
     * @param vectorFieldName the name of the vector field in the Solr schema
     * @param topK            the number of nearest neighbors to return
     * @param vectorString    the vector as a formatted string (e.g., "[0.1, 0.2, 0.3]")
     * @return a KNN query string ready for use in Solr
     * @throws IllegalArgumentException if vectorFieldName is null or empty, topK <= 0, or vectorString is null
     */
    public static String buildKnnQuery(String vectorFieldName, int topK, String vectorString) {
        if (vectorFieldName == null || vectorFieldName.trim().isEmpty()) {
            throw new IllegalArgumentException("Vector field name cannot be null or empty");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be greater than 0");
        }
        if (vectorString == null) {
            throw new IllegalArgumentException("Vector string cannot be null");
        }

        return String.format("{!knn f=%s topK=%d}%s", vectorFieldName, topK, vectorString);
    }

    /**
     * Builds a KNN query using the default vector field name.
     *
     * <p>Convenience method that calls {@link #buildKnnQuery(String, int, String)}
     * with {@link #DEFAULT_VECTOR_FIELD} as the field name.</p>
     *
     * @param topK         the number of nearest neighbors to return
     * @param vectorString the vector as a formatted string
     * @return a KNN query string ready for use in Solr
     * @throws IllegalArgumentException if topK <= 0 or vectorString is null
     */
    public static String buildKnnQuery(int topK, String vectorString) {
        return buildKnnQuery(DEFAULT_VECTOR_FIELD, topK, vectorString);
    }
}