package dev.aparikh.aipoweredsearch.embedding;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Service for generating and managing text embeddings using OpenAI.
 *
 * <p>This service provides a clean abstraction over the embedding model,
 * handling conversion between different embedding representations and
 * providing convenient methods for embedding generation.</p>
 *
 * <p>Currently uses OpenAI's text-embedding-3-small model which generates
 * 1536-dimensional embeddings optimized for semantic similarity search.</p>
 *
 * @author Aditya Parikh
 * @since 1.0.0
 */
@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    /**
     * Constructs a new EmbeddingService with the specified embedding model.
     *
     * @param embeddingModel the OpenAI embedding model to use for generating embeddings
     */
    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * Generates an embedding vector for the given text as a primitive float array.
     *
     * <p>This is the most efficient representation for storage and mathematical operations.</p>
     *
     * @param text the text to embed
     * @return a 1536-dimensional float array representing the text embedding
     * @throws IllegalArgumentException if text is null or empty
     */
    public float[] embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Text cannot be null or empty");
        }
        return embeddingModel.embed(text);
    }

    /**
     * Generates an embedding vector for the given text as a List of Floats.
     *
     * <p>This representation is more convenient for certain operations like
     * building Solr queries where collections are needed.</p>
     *
     * @param text the text to embed
     * @return a List of 1536 Float values representing the text embedding
     * @throws IllegalArgumentException if text is null or empty
     */
    public List<Float> embedAsList(String text) {
        float[] embedding = embed(text);
        return convertToList(embedding);
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
    public List<Float> convertToList(float[] embedding) {
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
    public String formatVectorForSolr(List<Float> embedding) {
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
    public String formatVectorForSolr(float[] embedding) {
        return formatVectorForSolr(convertToList(embedding));
    }

    /**
     * Generates an embedding for the given text and formats it directly for Solr KNN queries.
     *
     * <p>This is a convenience method that combines {@link #embed(String)} and
     * {@link #formatVectorForSolr(float[])} into a single operation, avoiding the need
     * to store intermediate representations.</p>
     *
     * <p>Example output: {@code "[0.123, -0.456, 0.789, ...]"}</p>
     *
     * @param text the text to embed and format
     * @return a Solr-compatible vector string representation
     * @throws IllegalArgumentException if text is null or empty
     */
    public String embedAndFormatForSolr(String text) {
        float[] embedding = embed(text);
        return formatVectorForSolr(embedding);
    }

    /**
     * Returns the underlying embedding model.
     *
     * <p>This method is provided for advanced use cases where direct access
     * to the embedding model is needed (e.g., batch processing).</p>
     *
     * @return the embedding model instance
     */
    public EmbeddingModel getEmbeddingModel() {
        return embeddingModel;
    }

}