package dev.aparikh.aipoweredsearch.evaluation;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Key-free, container-free checks of the evaluation harness itself: the checked-in data is
 * consistent, results aggregate as documented, and {@code rag.eval.props} parses as documented.
 * {@link RagEvaluationIT} only runs with API keys, so without these nothing in CI would notice
 * a broken label or a mis-aggregated report.
 */
class RagEvalHarnessTest {

    private static final double EPS = 1e-9;

    @Test
    void everyRelevantIdExistsInTheFixture() {
        Set<String> bookIds = RagEvalData.fixture().books().stream()
                .map(RagEvalData.Book::id)
                .collect(Collectors.toSet());

        assertThat(RagEvalData.evalSet().cases()).allSatisfy(evalCase ->
                assertThat(bookIds).as("relevantIds of %s", evalCase.id()).containsAll(evalCase.relevantIds()));
    }

    @Test
    void everyCaseIsWellFormed() {
        List<RagEvalData.EvalCase> cases = RagEvalData.evalSet().cases();

        assertThat(cases).extracting(RagEvalData.EvalCase::id).doesNotHaveDuplicates();
        assertThat(cases).allSatisfy(evalCase -> {
            assertThat(RagEvalReport.CATEGORIES).as("category of %s", evalCase.id()).contains(evalCase.category());
            assertThat(evalCase.turns()).as("turns of %s", evalCase.id()).isNotEmpty();
            assertThat(evalCase.relevantIds()).as("relevantIds of %s", evalCase.id()).isNotEmpty();
            if (evalCase.isFollowUp()) {
                assertThat(evalCase.standalone()).as("standalone rewrite of follow-up %s", evalCase.id()).isNotBlank();
            }
        });
    }

    @Test
    void summaryExcludesErroredCasesAndUndefinedMetrics() {
        RagEvalReport.CategorySummary summary = RagEvalReport.summarise("follow-up", List.of(
                result("a", 1.0, 0.5, 100, true, null),
                result("b", 0.5, Double.NaN, 300, null, null),
                result("c", 0.0, 0.0, 900, false, "boom")));

        assertThat(summary.cases()).isEqualTo(3);
        assertThat(summary.errors()).isEqualTo(1);
        // Errored case "c" is left out; NaN parity on "b" does not pull the mean towards zero.
        assertThat(summary.recallAt20()).isCloseTo(0.75, within(EPS));
        assertThat(summary.parity()).isCloseTo(0.5, within(EPS));
        assertThat(summary.latencyP95Ms()).isCloseTo(300, within(EPS));
        // Only "a" was judged; "b" has no verdict and is not counted as a failure.
        assertThat(summary.relevancePassRate()).isCloseTo(1.0, within(EPS));
        assertThat(summary.faithfulnessPassRate()).isNaN();
    }

    @Test
    void summaryOfOnlyErroredCasesIsUndefinedNotZero() {
        RagEvalReport.CategorySummary summary = RagEvalReport.summarise("follow-up",
                List.of(result("a", 0.0, 0.0, 100, null, "boom")));

        assertThat(summary.errors()).isEqualTo(1);
        assertThat(summary.recallAt20()).isNaN();
        assertThat(summary.latencyP50Ms()).isNaN();
    }

    @Test
    void reportAddsAnAllRowAndPrintsUndefinedMetricsAsNotApplicable() {
        RagEvalReport.Report report = RagEvalReport.build("baseline", "abc1234", Map.of(), false, List.of(
                result("fu-01", 1.0, Double.NaN, 100, null, null)));

        assertThat(report.summaries()).extracting(RagEvalReport.CategorySummary::category)
                .containsExactly("follow-up", "all");
        assertThat(RagEvalReport.toMarkdown(report)).contains("| fu-01 | 1.000 |").contains("n/a");
    }

    @Test
    void flagsSplitOnSemicolonsAndTheFirstEquals() {
        assertThat(RagEvaluationIT.parseFlags(" a.b=true ; c=x=y;; "))
                .containsExactly(Map.entry("a.b", "true"), Map.entry("c", "x=y"));
        assertThat(RagEvaluationIT.parseFlags("")).isEmpty();
    }

    @Test
    void flagsRejectEntriesWithoutAKey() {
        assertThatThrownBy(() -> RagEvaluationIT.parseFlags("a=1;novalue"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("novalue");
        assertThatThrownBy(() -> RagEvaluationIT.parseFlags("=1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RagEvalReport.CaseResult result(String id, double recall, double parity, double latencyMs,
                                                   @Nullable Boolean relevant, @Nullable String error) {
        return new RagEvalReport.CaseResult(id, "follow-up", recall, recall, parity, recall, 0, latencyMs, 0, 0,
                relevant, null, List.of(), List.of(), error);
    }
}
