package dev.aparikh.aipoweredsearch.search.rag;

import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The ordered document post-processors of the RAG pipeline (W4, #38):
 *
 * <pre>
 * [Jev filter, if enabled]  -> fail-open with a timeout, judged against the standalone query
 * [reranker, if enabled]    -> short-circuited when within top-k (optional), judged against the standalone query
 * </pre>
 *
 * <p>Each stage is also wrapped in an {@link ObservedDocumentPostProcessor} tagged with the
 * underlying processor's class name, so Grafana shows {@code JevDocumentFilter},
 * {@code RerankingDocumentPostProcessor} or {@code JevDocumentReranker}, not the wrappers.</p>
 *
 * @param processors the chain, in order; possibly empty
 */
public record RagPostProcessors(List<DocumentPostProcessor> processors) {

    public RagPostProcessors {
        processors = List.copyOf(processors);
    }

    /**
     * Assembles the chain.
     *
     * @param jevFilter         the passage filter, or null when screening is off
     * @param jevTimeout        upper bound on the filter; on expiry the documents pass through
     * @param reranker          the reranker (Claude or Jev), or null when reranking is off
     * @param rerankTopK        how many documents the reranker keeps
     * @param shortCircuit      skip the reranker when the candidates already fit in {@code rerankTopK}
     * @param observations      where per-stage observations are recorded
     */
    public static RagPostProcessors assemble(@Nullable DocumentPostProcessor jevFilter, Duration jevTimeout,
                                             @Nullable DocumentPostProcessor reranker, int rerankTopK,
                                             boolean shortCircuit, ObservationRegistry observations) {
        List<DocumentPostProcessor> chain = new ArrayList<>(2);
        if (jevFilter != null) {
            DocumentPostProcessor guarded = new FailOpenPostProcessor(
                    new StandaloneQueryAwarePostProcessor(jevFilter), jevTimeout, "jev-filter");
            chain.add(new ObservedDocumentPostProcessor(guarded, observations,
                    ObservedDocumentPostProcessor.processorName(jevFilter.getClass())));
        }
        if (reranker != null) {
            DocumentPostProcessor rerank = shortCircuit ? new RerankShortCircuit(reranker, rerankTopK) : reranker;
            chain.add(new ObservedDocumentPostProcessor(new StandaloneQueryAwarePostProcessor(rerank), observations,
                    ObservedDocumentPostProcessor.processorName(reranker.getClass())));
        }
        return new RagPostProcessors(chain);
    }
}
