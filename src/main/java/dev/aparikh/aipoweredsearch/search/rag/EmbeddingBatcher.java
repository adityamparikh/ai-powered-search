package dev.aparikh.aipoweredsearch.search.rag;

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
 * present, otherwise {@code Query.text()}. This is the same rule the retriever applies.</p>
 *
 * <p><strong>Fails safe.</strong> If the request fails or returns the wrong number of vectors, no
 * vector is set and each kNN leg embeds its own text as before. Slower, never wrong.</p>
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
     * @param queries planned queries whose contexts are mutable (the planner creates them)
     */
    public void embed(List<Query> queries) {
        if (queries.isEmpty()) {
            return;
        }
        List<String> texts = queries.stream().map(LegRouting::vectorText).toList();
        try {
            List<float[]> vectors = embeddingModel.embed(texts);
            if (vectors.size() != queries.size()) {
                log.warn("Batched embedding returned {} vectors for {} queries; legs will embed individually",
                        vectors.size(), queries.size());
                return;
            }
            for (int i = 0; i < queries.size(); i++) {
                queries.get(i).context().put(RagContextKeys.VECTOR, vectors.get(i));
            }
        } catch (RuntimeException e) {
            log.warn("Batched embedding failed; legs will embed individually: {}", e.getMessage());
        }
    }
}
