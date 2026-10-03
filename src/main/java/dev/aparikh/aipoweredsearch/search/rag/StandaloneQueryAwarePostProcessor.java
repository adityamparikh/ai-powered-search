package dev.aparikh.aipoweredsearch.search.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.List;

/**
 * Makes a {@link DocumentPostProcessor} judge documents against the standalone question rather
 * than the raw follow-up (W1, #36).
 *
 * <p>{@code RetrievalAugmentationAdvisor} hands post-processors the <em>original</em> query (W0
 * finding A1). On turn 2 of a conversation that is "Anything cheaper by the same author?", so a
 * reranker judges relevance against a question with no referent. When the planner has put the
 * standalone rewrite into the query's context under {@link RagContextKeys#STANDALONE}, this
 * wrapper passes the delegate a query with that text. Otherwise it passes the query through
 * untouched, so with the planner off it changes nothing.</p>
 */
public final class StandaloneQueryAwarePostProcessor implements DocumentPostProcessor {

    private final DocumentPostProcessor delegate;

    public StandaloneQueryAwarePostProcessor(DocumentPostProcessor delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        return delegate.process(standaloneOrOriginal(query), documents);
    }

    /** The wrapped post-processor. */
    public DocumentPostProcessor delegate() {
        return delegate;
    }

    static Query standaloneOrOriginal(Query query) {
        Object standalone = query.context().get(RagContextKeys.STANDALONE);
        if (standalone instanceof String text && !text.isBlank() && !text.equals(query.text())) {
            return query.mutate().text(text).build();
        }
        return query;
    }
}
