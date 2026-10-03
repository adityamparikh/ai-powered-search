package dev.aparikh.aipoweredsearch.search.rag;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;

import java.util.List;

/**
 * Embeds the vector-leg text of every planned query in <strong>one</strong> embedding request
 * (W2, #37).
 *
 * <p>With the planner on, a turn retrieves 1 + N queries, and each kNN leg would otherwise make
 * its own embedding request on the critical path. This makes one
 * {@link EmbeddingModel#embed(List)} call and puts each query's vector into its context under
 * {@link RagContextKeys#VECTOR}. The retriever then passes that vector to Solr and embeds nothing
 * (W5).</p>
 *
 * <p>A query's vector-leg text is {@link LegRouting#vectorText(Query)}: the HyDE passage when
 * present, otherwise {@code Query.text()}. This is the same rule the retriever applies. The vector
 * is computed from that text as it stands now, so this must run after every stage that sets it;
 * the planner calls it last.</p>
 *
 * <p><strong>Fails safe.</strong> If the request fails, returns the wrong number of vectors, or
 * returns an empty vector or vectors of differing lengths, no vector is set and each kNN leg embeds
 * its own text as before. Slower, never wrong.</p>
 */
public class EmbeddingBatcher {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBatcher.class);

    private final EmbeddingModel embeddingModel;

    public EmbeddingBatcher(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * Sets {@link RagContextKeys#VECTOR} on every query, using one embedding request.
     *
     * @param queries planned queries whose contexts are mutable (the planner creates them as
     *                {@code HashMap}s); a read-only context is logged with its exception and
     *                leaves that query and the rest to embed individually
     */
    public void embed(List<Query> queries) {
        if (queries.isEmpty()) {
            return;
        }
        List<String> texts = queries.stream().map(LegRouting::vectorText).toList();
        try {
            List<float[]> vectors = embeddingModel.embed(texts);
            String problem = problemWith(vectors, queries.size());
            if (problem != null) {
                log.warn("Batched embedding rejected ({}); legs will embed individually", problem);
                return;
            }
            for (int i = 0; i < queries.size(); i++) {
                queries.get(i).context().put(RagContextKeys.VECTOR, vectors.get(i));
            }
        } catch (RuntimeException e) {
            log.warn("Batched embedding failed; legs will embed individually", e);
        }
    }

    /**
     * Why the batch cannot be used, or null. A vector Solr would reject must not replace the
     * leg's own embedding, so an empty or inconsistently sized vector rejects the whole batch.
     */
    private static @Nullable String problemWith(List<float[]> vectors, int expected) {
        if (vectors.size() != expected) {
            return vectors.size() + " vectors for " + expected + " queries";
        }
        int dimensions = vectors.getFirst() == null ? 0 : vectors.getFirst().length;
        for (float[] vector : vectors) {
            if (vector == null || vector.length == 0 || vector.length != dimensions) {
                return "empty or inconsistently sized vector";
            }
        }
        return null;
    }
}
