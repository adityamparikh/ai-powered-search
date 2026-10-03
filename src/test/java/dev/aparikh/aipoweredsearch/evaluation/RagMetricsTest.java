package dev.aparikh.aipoweredsearch.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Hand-computed fixtures for the evaluation metrics. If one of these moves, every number in
 * docs/rag-eval-baseline.md moves with it.
 */
class RagMetricsTest {

    private static final double EPS = 1e-9;

    @Test
    void recallCountsRelevantHitsWithinTheCutoff() {
        // relevant = {a, c, e}; top-3 = [a, b, c] -> 2 of 3
        assertThat(RagMetrics.recallAtK(List.of("a", "b", "c", "d", "e"), Set.of("a", "c", "e"), 3))
                .isCloseTo(2.0 / 3.0, within(EPS));
    }

    @Test
    void recallWithCutoffBeyondListLengthUsesTheWholeList() {
        assertThat(RagMetrics.recallAtK(List.of("a"), Set.of("a", "b"), 20)).isCloseTo(0.5, within(EPS));
    }

    @Test
    void recallIgnoresDuplicateCandidates() {
        // a duplicated 'a' must not count twice
        assertThat(RagMetrics.recallAtK(List.of("a", "a"), Set.of("a", "b"), 2)).isCloseTo(0.5, within(EPS));
    }

    @Test
    void recallIsUndefinedWithoutRelevantDocuments() {
        assertThat(RagMetrics.recallAtK(List.of("a"), Set.of(), 20)).isNaN();
    }

    @Test
    void recallAfterRerankIsShareOfRelevantDocumentsThatReachedTheContext() {
        // relevant = {a, b, c}; reranking kept a and an irrelevant x -> 1 of 3
        assertThat(RagMetrics.recallAfterRerank(List.of("a", "x"), Set.of("a", "b", "c")))
                .isCloseTo(1.0 / 3.0, within(EPS));
    }

    @Test
    void recallAfterRerankIsNotRaisedByPassingFewerDocuments() {
        // dropping x lifts precision from 0.5 to 1.0 but leaves recall after rerank at 1 of 3
        Set<String> relevant = Set.of("a", "b", "c");
        assertThat(RagMetrics.precision(List.of("a"), relevant)).isCloseTo(1.0, within(EPS));
        assertThat(RagMetrics.recallAfterRerank(List.of("a"), relevant)).isCloseTo(1.0 / 3.0, within(EPS));
    }

    @Test
    void recallAfterRerankIsZeroForAnEmptyContextAndUndefinedWithoutRelevantDocuments() {
        assertThat(RagMetrics.recallAfterRerank(List.of(), Set.of("a"))).isZero();
        assertThat(RagMetrics.recallAfterRerank(List.of("a"), Set.of())).isNaN();
    }

    @Test
    void parityIsShareOfStandaloneCandidatesTheFollowUpFound() {
        // standalone = {a, b, c, d}; follow-up found a, d and an extra x -> 2 of 4
        assertThat(RagMetrics.parity(List.of("x", "a", "d"), List.of("a", "b", "c", "d")))
                .isCloseTo(0.5, within(EPS));
    }

    @Test
    void parityIsOneWhenSetsMatchInAnyOrder() {
        assertThat(RagMetrics.parity(List.of("c", "b", "a"), List.of("a", "b", "c"))).isCloseTo(1.0, within(EPS));
    }

    @Test
    void parityIsUndefinedForAnEmptyStandaloneSet() {
        assertThat(RagMetrics.parity(List.of("a"), List.of())).isNaN();
    }

    @Test
    void precisionIsShareOfContextThatIsRelevant() {
        // context = [a, b, c, d, e], relevant hits a and e -> 2 of 5
        assertThat(RagMetrics.precision(List.of("a", "b", "c", "d", "e"), Set.of("a", "e", "z")))
                .isCloseTo(0.4, within(EPS));
    }

    @Test
    void precisionIsUndefinedForAnEmptyContext() {
        assertThat(RagMetrics.precision(List.of(), Set.of("a"))).isNaN();
    }

    @Test
    void injectionCountMatchesThePrefixOnly() {
        assertThat(RagMetrics.injectionCount(List.of("inj-01", "grrm-01", "inj-04", "injury-1", "inj-01")))
                .isEqualTo(2);
    }

    @Test
    void percentileUsesNearestRank() {
        List<Double> latencies = List.of(15.0, 20.0, 35.0, 40.0, 50.0);
        // nearest rank: p50 -> ceil(2.5) = 3rd = 35; p95 -> ceil(4.75) = 5th = 50; p20 -> 1st
        assertThat(RagMetrics.percentile(latencies, 50)).isEqualTo(35.0);
        assertThat(RagMetrics.percentile(latencies, 95)).isEqualTo(50.0);
        assertThat(RagMetrics.percentile(latencies, 20)).isEqualTo(15.0);
    }

    @Test
    void percentileIgnoresNaNAndRejectsOutOfRange() {
        assertThat(RagMetrics.percentile(List.of(Double.NaN, 3.0), 100)).isEqualTo(3.0);
        assertThat(RagMetrics.percentile(List.of(), 50)).isNaN();
        assertThatThrownBy(() -> RagMetrics.percentile(List.of(1.0), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void meanSkipsUndefinedValues() {
        assertThat(RagMetrics.mean(List.of(1.0, Double.NaN, 0.0))).isCloseTo(0.5, within(EPS));
        assertThat(RagMetrics.mean(List.of(Double.NaN))).isNaN();
    }
}
