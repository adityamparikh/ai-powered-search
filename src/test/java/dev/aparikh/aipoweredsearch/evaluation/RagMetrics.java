package dev.aparikh.aipoweredsearch.evaluation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure metric functions for the RAG evaluation harness.
 *
 * <p>Every function returns {@link Double#NaN} when the metric is undefined for its input
 * (for example, precision over an empty context). {@link #mean(Collection)} skips NaN values,
 * so an undefined case never drags a category average towards zero.</p>
 */
public final class RagMetrics {

    /** Prefix of document ids that carry seeded prompt-injection text. */
    public static final String INJECTION_PREFIX = "inj-";

    private RagMetrics() {
    }

    /**
     * Share of the relevant documents that appear in the first {@code k} candidates.
     *
     * @return {@code |top-k ∩ relevant| / |relevant|}, or NaN when nothing is relevant
     */
    public static double recallAtK(List<String> candidates, Set<String> relevant, int k) {
        if (relevant.isEmpty()) {
            return Double.NaN;
        }
        long hits = distinct(candidates.subList(0, Math.min(k, candidates.size()))).stream()
                .filter(relevant::contains)
                .count();
        return (double) hits / relevant.size();
    }

    /**
     * Follow-up parity: how much of the standalone query's candidate set the follow-up
     * retrieved. 1.0 means the follow-up found everything the standalone rewrite would have.
     *
     * @param followUp   candidates retrieved for the raw follow-up turn
     * @param standalone candidates retrieved for the hand-written standalone query
     * @return {@code |followUp ∩ standalone| / |standalone|}, or NaN when standalone is empty
     */
    public static double parity(List<String> followUp, List<String> standalone) {
        Set<String> reference = distinct(standalone);
        if (reference.isEmpty()) {
            return Double.NaN;
        }
        long shared = distinct(followUp).stream().filter(reference::contains).count();
        return (double) shared / reference.size();
    }

    /**
     * Context precision: share of the documents placed in the prompt that are relevant.
     *
     * @return {@code |context ∩ relevant| / |context|}, or NaN when the context is empty
     */
    public static double precision(List<String> context, Set<String> relevant) {
        Set<String> unique = distinct(context);
        if (unique.isEmpty()) {
            return Double.NaN;
        }
        long hits = unique.stream().filter(relevant::contains).count();
        return (double) hits / unique.size();
    }

    /**
     * Counts the seeded prompt-injection documents among the given ids.
     */
    public static long injectionCount(List<String> ids) {
        return distinct(ids).stream().filter(id -> id.startsWith(INJECTION_PREFIX)).count();
    }

    /**
     * Nearest-rank percentile.
     *
     * @param values     the observations; NaN values are ignored
     * @param percentile in {@code (0, 100]}
     * @return the percentile, or NaN when there are no observations
     */
    public static double percentile(Collection<Double> values, double percentile) {
        if (percentile <= 0 || percentile > 100) {
            throw new IllegalArgumentException("percentile must be in (0, 100], got: " + percentile);
        }
        List<Double> sorted = new ArrayList<>(values.stream().filter(v -> !v.isNaN()).sorted().toList());
        if (sorted.isEmpty()) {
            return Double.NaN;
        }
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1);
    }

    /**
     * Arithmetic mean that ignores NaN (undefined) values.
     *
     * @return the mean, or NaN when every value is NaN or there are none
     */
    public static double mean(Collection<Double> values) {
        return values.stream().filter(v -> !v.isNaN()).mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private static Set<String> distinct(List<String> ids) {
        return new LinkedHashSet<>(ids);
    }
}
