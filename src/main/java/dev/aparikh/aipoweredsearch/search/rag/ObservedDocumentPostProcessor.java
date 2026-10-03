package dev.aparikh.aipoweredsearch.search.rag;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.List;

/**
 * Times a {@link DocumentPostProcessor} as a {@value RagObservations#POSTPROCESS} observation,
 * tagged with the delegate's simple class name. Behaviour is otherwise unchanged.
 */
public final class ObservedDocumentPostProcessor implements DocumentPostProcessor {

    private final DocumentPostProcessor delegate;
    private final ObservationRegistry registry;
    private final String processorName;

    /**
     * @param delegate      the post-processor to time
     * @param registry      where observations are recorded
     * @param processorName the {@value RagObservations#PROCESSOR_TAG} tag value; keep it low-cardinality
     */
    public ObservedDocumentPostProcessor(DocumentPostProcessor delegate, ObservationRegistry registry,
                                         String processorName) {
        this.delegate = delegate;
        this.registry = registry;
        this.processorName = processorName;
    }

    /**
     * Wraps {@code delegate}, tagging it with its simple class name (or, for an anonymous
     * subclass, its superclass's).
     */
    public static ObservedDocumentPostProcessor of(DocumentPostProcessor delegate, ObservationRegistry registry) {
        return new ObservedDocumentPostProcessor(delegate, registry, processorName(delegate.getClass()));
    }

    static String processorName(Class<?> type) {
        Class<?> named = type;
        while (named.getSimpleName().isEmpty() && named.getSuperclass() != null) {
            named = named.getSuperclass();
        }
        return named.getSimpleName();
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        return RagObservations.observe(registry, RagObservations.POSTPROCESS,
                RagObservations.PROCESSOR_TAG, processorName,
                () -> delegate.process(query, documents));
    }
}
