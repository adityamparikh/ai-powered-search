package dev.aparikh.aipoweredsearch.search.rag;

import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bounds an optional post-processor by a timeout and makes it fail open (W4, #38).
 *
 * <p>Screening with a remote service is an optimisation, not a dependency: if the delegate
 * throws, or has not answered within {@code timeout}, the documents pass through unchanged and a
 * WARN is logged. Screening is therefore best-effort. The delegate runs on a virtual thread, which
 * is interrupted on timeout. The current trace is carried onto it.</p>
 *
 * <p>Interrupting does not stop calls already in flight: {@code TypeSafeClient.systemOneAll} runs
 * them on its own pool and waits uninterruptibly, so they finish (and are billed) in the
 * background. Each is bounded by {@code spring.ai.typesafe.timeout}.</p>
 *
 * <p>Every pass-through is recorded as a {@value #FAIL_OPEN_EVENT} event on the current observation
 * (the enclosing {@code rag.postprocess}), which Micrometer counts as
 * {@code rag.postprocess.fail.open}. A delegate that discards every document is not a failure: the
 * empty list is returned, with a WARN and a {@value #DISCARDED_ALL_EVENT} event.</p>
 */
public final class FailOpenPostProcessor implements DocumentPostProcessor {

    /** Observation event for a pass-through on timeout or error. */
    public static final String FAIL_OPEN_EVENT = "fail.open";

    /** Observation event for a delegate that kept none of the documents. */
    public static final String DISCARDED_ALL_EVENT = "discarded.all";

    private static final Logger log = LoggerFactory.getLogger(FailOpenPostProcessor.class);

    private final DocumentPostProcessor delegate;
    private final Duration timeout;
    private final String name;
    private final ObservationRegistry registry;
    private final ContextSnapshotFactory contextSnapshotFactory = ContextSnapshotFactory.builder().build();

    /**
     * @param delegate the post-processor to guard
     * @param timeout  how long to wait before passing the documents through unchanged
     * @param name     used in log messages
     */
    public FailOpenPostProcessor(DocumentPostProcessor delegate, Duration timeout, String name) {
        this(delegate, timeout, name, ObservationRegistry.NOOP);
    }

    /**
     * @param delegate the post-processor to guard
     * @param timeout  how long to wait before passing the documents through unchanged
     * @param name     used in log messages
     * @param registry whose current observation receives the fail-open and discarded-all events
     */
    public FailOpenPostProcessor(DocumentPostProcessor delegate, Duration timeout, String name,
                                 ObservationRegistry registry) {
        this.delegate = delegate;
        this.timeout = timeout;
        this.name = name;
        this.registry = registry;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents.isEmpty()) {
            return documents;
        }
        FutureTask<List<Document>> task = new FutureTask<>(
                contextSnapshotFactory.captureAll().wrap(() -> delegate.process(query, documents)));
        Thread.ofVirtual().name("rag-" + name).start(task);
        try {
            List<Document> result = task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (result.isEmpty()) {
                event(DISCARDED_ALL_EVENT);
                log.warn("{} discarded all {} documents; the prompt gets no retrieved context", name,
                        documents.size());
            }
            return result;
        } catch (TimeoutException e) {
            task.cancel(true);
            log.warn("{} did not answer within {} ms; passing {} documents through unchanged",
                    name, timeout.toMillis(), documents.size());
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for {}; passing documents through unchanged", name);
        } catch (ExecutionException e) {
            log.warn("{} failed; passing {} documents through unchanged: {}", name, documents.size(),
                    String.valueOf(e.getCause()));
            log.debug("{} failure", name, e.getCause());
        }
        event(FAIL_OPEN_EVENT);
        return documents;
    }

    private void event(String event) {
        Observation current = registry.getCurrentObservation();
        if (current != null) {
            current.event(Observation.Event.of(event));
        }
    }

    /** The guarded post-processor. */
    DocumentPostProcessor delegate() {
        return delegate;
    }
}
