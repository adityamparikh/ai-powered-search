package dev.aparikh.aipoweredsearch.search.rag;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.rag.Query;

/**
 * Decides what each retrieval leg searches with (W2, #37).
 *
 * <p>A chatty question is a poor input for both legs. BM25 wants distinctive terms, and kNN
 * compares best with text shaped like the documents themselves. The planner therefore gives each
 * leg its own input through the query context. Every input falls back to {@code Query.text()},
 * so with the planner off nothing changes.</p>
 *
 * <ul>
 *   <li><strong>BM25 leg:</strong> {@link RagContextKeys#KEYWORD_QUERY}, else the query text. It
 *       <em>never</em> sees a HyDE passage, whose generic prose would match almost everything
 *       lexically.</li>
 *   <li><strong>kNN leg:</strong> a precomputed {@link RagContextKeys#VECTOR}; else the embedding
 *       of {@link RagContextKeys#VECTOR_TEXT} (a HyDE passage); else the embedding of the query
 *       text.</li>
 * </ul>
 */
public final class LegRouting {

    private static final Logger log = LoggerFactory.getLogger(LegRouting.class);

    private LegRouting() {
    }

    /** The BM25 leg's text: the planner's keyword query when present, else the query text. */
    public static String keywordText(Query query) {
        return nonBlankString(query.context().get(RagContextKeys.KEYWORD_QUERY), query.text());
    }

    /** The text the kNN leg embeds when no vector is precomputed: a HyDE passage, else the query text. */
    public static String vectorText(Query query) {
        return nonBlankString(query.context().get(RagContextKeys.VECTOR_TEXT), query.text());
    }

    /**
     * The embedding an earlier stage already computed for this query, if any.
     *
     * <p>A value that is not a non-empty {@code float[]} is ignored with a warning and the kNN leg
     * embeds {@link #vectorText} instead, so a contract violation by an earlier stage costs one
     * embedding call rather than a failed kNN query.</p>
     */
    public static float @Nullable [] vector(Query query) {
        Object value = query.context().get(RagContextKeys.VECTOR);
        if (value == null) {
            return null;
        }
        if (value instanceof float[] vector && vector.length > 0) {
            return vector;
        }
        log.warn("Ignoring {} of type {}: expected a non-empty float[]; embedding the query text instead",
                RagContextKeys.VECTOR, value.getClass().getSimpleName());
        return null;
    }

    private static String nonBlankString(@Nullable Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }
}
