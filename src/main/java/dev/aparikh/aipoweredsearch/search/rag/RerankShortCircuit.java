package dev.aparikh.aipoweredsearch.search.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.List;

/**
 * Skips a reranker when there is nothing left for it to trim (W4, #38).
 *
 * <p>A reranker earns its cost by <em>discarding</em>. When the Jev filter has already cut the
 * candidates to {@code top-k} or fewer, this wrapper returns them as they are and saves a model
 * call. That is not free: the Claude reranker can also reject candidates it judges irrelevant, even
 * below {@code top-k}, and skipping it lets every survivor into the prompt. Hence opt-in
 * ({@code search.rag.rerank.short-circuit}, default {@code false}).</p>
 */
public final class RerankShortCircuit implements DocumentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(RerankShortCircuit.class);

    private final DocumentPostProcessor reranker;
    private final int topK;

    public RerankShortCircuit(DocumentPostProcessor reranker, int topK) {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive, got: " + topK);
        }
        this.reranker = reranker;
        this.topK = topK;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents.size() <= topK) {
            log.debug("Skipping rerank: {} candidates is within top-k {}", documents.size(), topK);
            return documents;
        }
        return reranker.process(query, documents);
    }

    /** The wrapped reranker. */
    DocumentPostProcessor delegate() {
        return reranker;
    }
}
