package dev.aparikh.aipoweredsearch.search.rag;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.function.Supplier;

/**
 * Names and helpers for the per-stage Micrometer observations of the RAG pipeline.
 *
 * <p>Each observation becomes a span in traces and a timer in metrics, exported as
 * {@code <name>_seconds_bucket} with percentile histograms enabled for {@code rag.*} in
 * {@code application.properties}. Tags are low-cardinality by design: the leg name or a
 * processor class name, never a query or a document id.</p>
 */
public final class RagObservations {

    /** Retrieval of one query; tag {@value #LEG_TAG} = {@code hybrid}, {@code keyword} or {@code vector}. */
    public static final String RETRIEVE = "rag.retrieve";

    /** Joining the per-query result lists into one candidate list. */
    public static final String JOIN = "rag.join";

    /** One document post-processor; tag {@value #PROCESSOR_TAG} = the processor's simple class name. */
    public static final String POSTPROCESS = "rag.postprocess";

    /** The query planner's model call (W1). */
    public static final String PLAN = "rag.plan";

    public static final String LEG_TAG = "leg";
    public static final String PROCESSOR_TAG = "processor";

    private RagObservations() {
    }

    /**
     * Runs {@code work} inside an observation with one low-cardinality tag.
     *
     * <p>Exceptions propagate unchanged and are recorded on the observation as errors.</p>
     */
    public static <T> T observe(ObservationRegistry registry, String name, String tagKey, String tagValue,
                                Supplier<T> work) {
        return Observation.createNotStarted(name, registry)
                .lowCardinalityKeyValue(tagKey, tagValue)
                .observe(work);
    }

    /**
     * Runs {@code work} inside an untagged observation.
     */
    public static <T> T observe(ObservationRegistry registry, String name, Supplier<T> work) {
        return Observation.createNotStarted(name, registry).observe(work);
    }
}
