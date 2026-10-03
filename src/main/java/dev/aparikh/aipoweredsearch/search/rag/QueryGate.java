package dev.aparikh.aipoweredsearch.search.rag;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Decides whether a question needs the query planner at all (W6, #39).
 *
 * <p>A standalone keyword lookup on the first turn ("A Clash of Kings") gains nothing from
 * rewriting, variants or HyDE, but would pay the planner's latency. The gate sends such a question
 * down today's path, retrieving on the raw text with no model call, when <em>all</em> of these
 * hold:</p>
 * <ul>
 *   <li>the conversation has no earlier turns: there is nothing to resolve;</li>
 *   <li>the question has at most {@code maxTokens} whitespace-separated tokens;</li>
 *   <li>it contains none of the conversational {@code markers}: pronouns and comparatives such as
 *       "it", "same" or "cheaper" that only make sense against something said before.</li>
 * </ul>
 *
 * <p>Every decision increments the {@code rag.gate} counter, tagged {@code outcome=skipped} or
 * {@code outcome=planned}. The ratio of the two is the gate's hit rate.</p>
 *
 * <p>Pattern: adaptive retrieval, as in Jeong et al., <a href="https://arxiv.org/abs/2403.14403">Adaptive-RAG
 * (2024)</a>: route simple queries down the cheap path and spend the extra stage only where it helps.</p>
 */
public final class QueryGate {

    /** Counter name; tag {@value #OUTCOME_TAG} is {@value #SKIPPED} or {@value #PLANNED}. */
    public static final String METRIC = "rag.gate";
    public static final String OUTCOME_TAG = "outcome";
    public static final String SKIPPED = "skipped";
    public static final String PLANNED = "planned";

    /** Pronouns and comparatives that refer back to an earlier turn. */
    public static final List<String> DEFAULT_MARKERS = List.of(
            "it", "its", "that", "those", "them", "same", "more", "another", "else", "cheaper", "newer", "older");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final boolean enabled;
    private final int maxTokens;
    private final Set<String> markers;
    private final Counter skipped;
    private final Counter planned;

    /**
     * @param enabled   {@code search.rag.gate.enabled}; when false every question is planned
     * @param maxTokens {@code search.rag.gate.max-tokens}: the longest question that can skip planning
     * @param markers   {@code search.rag.gate.markers}: words that force planning; matched case-insensitively
     * @param meters    where the {@value #METRIC} counter is registered
     */
    public QueryGate(boolean enabled, int maxTokens, Collection<String> markers, MeterRegistry meters) {
        this.enabled = enabled;
        this.maxTokens = maxTokens;
        this.markers = markers.stream()
                .map(marker -> marker.strip().toLowerCase(Locale.ROOT))
                .filter(marker -> !marker.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.skipped = Counter.builder(METRIC).tag(OUTCOME_TAG, SKIPPED)
                .description("RAG turns that skipped the query planner").register(meters);
        this.planned = Counter.builder(METRIC).tag(OUTCOME_TAG, PLANNED)
                .description("RAG turns sent to the query planner").register(meters);
    }

    /**
     * Decides, and records the outcome.
     *
     * @param question       the user's latest message
     * @param hasEarlierTurns whether the conversation has user or assistant turns before it
     * @return true to skip the planner and retrieve on the raw question
     */
    public boolean skipPlanning(String question, boolean hasEarlierTurns) {
        boolean skip = wouldSkip(question, hasEarlierTurns);
        (skip ? skipped : planned).increment();
        return skip;
    }

    /** The decision alone, without recording it. */
    public boolean wouldSkip(String question, boolean hasEarlierTurns) {
        if (!enabled || hasEarlierTurns) {
            return false;
        }
        List<String> tokens = WHITESPACE.splitAsStream(question.strip()).toList();
        if (question.isBlank() || tokens.size() > maxTokens) {
            return false;
        }
        for (String token : tokens) {
            // Lower-case, drop apostrophes ("it's" -> "its") and edge punctuation ("same?" -> "same").
            String word = token.toLowerCase(Locale.ROOT)
                    .replace("'", "").replace("\u2019", "")
                    .replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
            if (markers.contains(word)) {
                return false;
            }
        }
        return true;
    }
}
