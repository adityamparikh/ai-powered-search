package dev.aparikh.aipoweredsearch.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for vector formatting operations.
 *
 * <p>Provides static methods for converting vector representations between different formats,
 * particularly for Apache Solr's KNN query parser requirements.</p>
 *
 * @author Aditya Parikh
 * @since 1.0.0
 */
public final class VectorFormatUtils {

    private VectorFormatUtils() {
        // Private constructor to prevent instantiation
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Converts a primitive float array to a List of Float objects.
     *
     * <p>This utility method is useful when working with APIs that require
     * collection types rather than primitive arrays.</p>
     *
     * @param embedding the primitive float array to convert
     * @return a List containing the same values as Float objects
     */
    public static List<Float> convertToList(float[] embedding) {
        List<Float> result = new ArrayList<>(embedding.length);
        for (float f : embedding) {
            result.add(f);
        }
        return result;
    }

    /**
     * Converts a List of Float values to a Solr-compatible vector string format.
     *
     * <p>Converts a list like {@code [0.1, 0.2, 0.3]} to the string {@code "[0.1, 0.2, 0.3]"}
     * which is the format required by Solr's KNN query parser.</p>
     *
     * @param embedding the embedding vector as a List of Floats
     * @return a string representation suitable for Solr KNN queries
     */
    public static String formatVectorForSolr(List<Float> embedding) {
        return embedding.stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    /**
     * Converts a primitive float array to a Solr-compatible vector string format.
     *
     * <p>Converts an array like {@code [0.1, 0.2, 0.3]} to the string {@code "[0.1, 0.2, 0.3]"}
     * which is the format required by Solr's KNN query parser.</p>
     *
     * @param embedding the embedding vector as a primitive float array
     * @return a string representation suitable for Solr KNN queries
     */
    public static String formatVectorForSolr(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}