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
     *
     * @throws IllegalArgumentException if {@code delegate} is a lambda, or an anonymous class with
     *         no named superclass. Neither has a meaningful, stable name, so use the constructor
     *         and pass one explicitly.
     */
    public static ObservedDocumentPostProcessor of(DocumentPostProcessor delegate, ObservationRegistry registry) {
        return new ObservedDocumentPostProcessor(delegate, registry, processorName(delegate.getClass()));
    }

    /**
     * A low-cardinality name for a post-processor class: its simple name, or for an anonymous
     * subclass its nearest named superclass's.
     */
    public static String processorName(Class<?> type) {
        Class<?> named = type;
        while (named.getSimpleName().isEmpty() && named.getSuperclass() != null) {
            named = named.getSuperclass();
        }
        // A lambda's class is hidden and named like Foo$$Lambda/0x..., which changes between
        // runs; an anonymous interface implementation would otherwise be tagged "Object".
        if (type.isHidden() || named == Object.class) {
            throw new IllegalArgumentException(type.getName()
                    + " has no stable class name; pass an explicit processor name");
        }
        return named.getSimpleName();
    }

    /** The tag value this processor is recorded under. */
    public String processorName() {
        return processorName;
    }

    /** The timed post-processor. */
    public DocumentPostProcessor delegate() {
        return delegate;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        return RagObservations.observe(registry, RagObservations.POSTPROCESS,
                RagObservations.PROCESSOR_TAG, processorName,
                () -> delegate.process(query, documents));
    }
}
