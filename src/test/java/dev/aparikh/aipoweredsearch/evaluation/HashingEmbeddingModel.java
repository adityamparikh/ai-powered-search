package dev.aparikh.aipoweredsearch.evaluation;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A deterministic, offline {@link EmbeddingModel} for regression tests: a bag-of-words feature
 * hash into 1536 dimensions, L2-normalised.
 *
 * <p>Texts that share words land close together under cosine similarity. That is crude but
 * stable, and good enough to exercise kNN retrieval, fusion and ordering without a network call
 * or an API key. It is not a stand-in for real embedding <em>quality</em>; use
 * {@code RagEvaluationIT} for that.</p>
 */
public class HashingEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSIONS = 1536;

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "the", "of", "to", "in", "on", "for", "by", "with", "is", "are", "was",
            "his", "her", "its", "their", "as", "at", "from", "that", "this", "it", "be", "or", "any");

    private final AtomicInteger singleCalls = new AtomicInteger();
    private final AtomicInteger batchCalls = new AtomicInteger();

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        batchCalls.incrementAndGet();
        List<Embedding> embeddings = new ArrayList<>();
        List<String> texts = request.getInstructions();
        for (int i = 0; i < texts.size(); i++) {
            embeddings.add(new Embedding(vector(texts.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(String text) {
        singleCalls.incrementAndGet();
        return vector(text);
    }

    @Override
    public float[] embed(Document document) {
        return vector(document.getText() == null ? "" : document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    /** Number of {@link #embed(String)} calls so far. */
    public int singleCalls() {
        return singleCalls.get();
    }

    /** Number of batch ({@link #call(EmbeddingRequest)}) requests so far. */
    public int batchCalls() {
        return batchCalls.get();
    }

    public void resetCounts() {
        singleCalls.set(0);
        batchCalls.set(0);
    }

    static float[] vector(String text) {
        float[] vector = new float[DIMENSIONS];
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.isEmpty() || STOPWORDS.contains(token)) {
                continue;
            }
            int hash = token.hashCode();
            int bucket = Math.floorMod(hash, DIMENSIONS);
            // A second, independent bit picks the sign so unrelated tokens tend to cancel.
            float sign = ((hash >>> 16) & 1) == 0 ? 1f : -1f;
            vector[bucket] += sign;
        }
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        if (norm == 0) {
            // Cosine similarity is undefined for the zero vector; give empty text a fixed direction.
            vector[0] = 1f;
            return vector;
        }
        float inverse = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= inverse;
        }
        return vector;
    }
}
