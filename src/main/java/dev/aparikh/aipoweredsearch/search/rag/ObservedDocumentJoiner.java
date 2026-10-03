package dev.aparikh.aipoweredsearch.search.rag;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;

import java.util.List;
import java.util.Map;

/**
 * Times a {@link DocumentJoiner} as a {@value RagObservations#JOIN} observation. Behaviour is
 * otherwise unchanged.
 */
public final class ObservedDocumentJoiner implements DocumentJoiner {

    private final DocumentJoiner delegate;
    private final ObservationRegistry registry;

    public ObservedDocumentJoiner(DocumentJoiner delegate, ObservationRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    @Override
    public List<Document> join(Map<Query, List<List<Document>>> documentsForQuery) {
        return RagObservations.observe(registry, RagObservations.JOIN, () -> delegate.join(documentsForQuery));
    }
}
