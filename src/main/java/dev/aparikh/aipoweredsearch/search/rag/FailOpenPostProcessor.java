package dev.aparikh.aipoweredsearch.search.rag;

import io.micrometer.context.ContextSnapshotFactory;
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
 * WARN is logged. The delegate runs on a virtual thread, which is interrupted on timeout. The
 * current trace is carried onto it.</p>
 */
public final class FailOpenPostProcessor implements DocumentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(FailOpenPostProcessor.class);

    private final DocumentPostProcessor delegate;
    private final Duration timeout;
    private final String name;
    private final ContextSnapshotFactory contextSnapshotFactory = ContextSnapshotFactory.builder().build();

    /**
     * @param delegate the post-processor to guard
     * @param timeout  how long to wait before passing the documents through unchanged
     * @param name     used in log messages
     */
    public FailOpenPostProcessor(DocumentPostProcessor delegate, Duration timeout, String name) {
        this.delegate = delegate;
        this.timeout = timeout;
        this.name = name;
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
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
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
        }
        return documents;
    }

    /** The guarded post-processor. */
    public DocumentPostProcessor delegate() {
        return delegate;
    }
}
