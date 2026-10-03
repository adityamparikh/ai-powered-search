package dev.aparikh.aipoweredsearch.evaluation;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

/**
 * Aggregates per-case results into per-category metrics and writes
 * {@code build/reports/rag-eval/report.{md,json}}.
 */
public final class RagEvalReport {

    /** Category order used in every table. */
    public static final List<String> CATEGORIES = List.of("follow-up", "filter", "vocab-gap", "injection", "keyword");

    /**
     * Outcome of one evaluation case. Metrics that do not apply are {@code NaN}.
     *
     * @param recallAt20         recall@20 over the fused candidates (before reranking)
     * @param recallAfterRerank  recall over {@code DOCUMENT_CONTEXT} (after reranking)
     * @param parity             follow-up parity against the standalone query's candidates
     * @param precision          context precision over {@code DOCUMENT_CONTEXT}
     * @param injections         seeded {@code inj-} documents that reached {@code DOCUMENT_CONTEXT}
     * @param latencyMs          wall-clock time of the final {@code /ask}
     * @param tokens             Claude tokens (prompt + completion) spent by the final {@code /ask}
     * @param modelCalls         Claude calls made by the final {@code /ask}
     * @param relevant           judge verdict on answer relevance, or null when not judged
     * @param faithful           judge verdict on faithfulness to the context, or null when not judged
     */
    public record CaseResult(String id, String category, double recallAt20, double recallAfterRerank,
                             double parity, double precision, long injections, double latencyMs, long tokens,
                             long modelCalls, @Nullable Boolean relevant, @Nullable Boolean faithful,
                             List<String> candidates, List<String> context, @Nullable String error) {
    }

    /** Aggregated metrics for one category (or {@code all}). */
    public record CategorySummary(String category, int cases, int errors, double recallAt20,
                                  double recallAfterRerank, double parity, double precision, long injections,
                                  double latencyP50Ms, double latencyP95Ms, double meanTokens, double relevancePassRate, double faithfulnessPassRate) {
    }

    public record Report(String label, String timestamp, String gitSha, Map<String, String> flags,
                         boolean judged, List<CategorySummary> summaries, List<CaseResult> cases) {
    }

    private RagEvalReport() {
    }

    public static Report build(String label, String gitSha, Map<String, String> flags, boolean judged,
                               List<CaseResult> results) {
        List<CategorySummary> summaries = new ArrayList<>();
        for (String category : CATEGORIES) {
            List<CaseResult> inCategory = results.stream().filter(r -> r.category().equals(category)).toList();
            if (!inCategory.isEmpty()) {
                summaries.add(summarise(category, inCategory));
            }
        }
        summaries.add(summarise("all", results));
        return new Report(label, Instant.now().toString(), gitSha, new LinkedHashMap<>(flags), judged, summaries, results);
    }

    static CategorySummary summarise(String category, List<CaseResult> results) {
        List<CaseResult> ok = results.stream().filter(r -> r.error() == null).toList();
        List<Double> latencies = ok.stream().map(CaseResult::latencyMs).toList();
        return new CategorySummary(
                category,
                results.size(),
                results.size() - ok.size(),
                mean(ok, CaseResult::recallAt20),
                mean(ok, CaseResult::recallAfterRerank),
                mean(ok, CaseResult::parity),
                mean(ok, CaseResult::precision),
                ok.stream().mapToLong(CaseResult::injections).sum(),
                RagMetrics.percentile(latencies, 50),
                RagMetrics.percentile(latencies, 95),
                mean(ok, CaseResult::tokens),
                passRate(ok.stream().map(CaseResult::relevant).toList()),
                passRate(ok.stream().map(CaseResult::faithful).toList()));
    }

    public static void write(Report report, Path directory) throws IOException {
        Files.createDirectories(directory);
        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        // Undefined metrics are written as the string "NaN" (Jackson's WRITE_NAN_AS_STRINGS default).
        String json = mapper.writeValueAsString(report);
        String markdown = toMarkdown(report);
        Files.writeString(directory.resolve("report.json"), json);
        Files.writeString(directory.resolve("report.md"), markdown);
        // Labelled copies make it easy to diff a flag-on run against the baseline.
        Files.writeString(directory.resolve("report-" + report.label() + ".json"), json);
        Files.writeString(directory.resolve("report-" + report.label() + ".md"), markdown);
    }

    public static String toMarkdown(Report report) {
        StringBuilder md = new StringBuilder();
        md.append("# RAG evaluation: ").append(report.label()).append("\n\n");
        md.append("- Timestamp: ").append(report.timestamp()).append("\n");
        md.append("- Commit: `").append(report.gitSha()).append("`\n");
        md.append("- Flags: ").append(report.flags().isEmpty() ? "none (all stages off)" : report.flags()).append("\n");
        md.append("- Judge: ").append(report.judged() ? "Ollama bespoke-minicheck" : "disabled").append("\n\n");
        md.append("| Category | Cases | Errors | Recall@20 | Recall after rerank | Follow-up parity | Context precision | Injections in context | p50 ms | p95 ms | Tokens/ask | Relevance | Faithfulness |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (CategorySummary s : report.summaries()) {
            md.append("| ").append(s.category())
                    .append(" | ").append(s.cases())
                    .append(" | ").append(s.errors())
                    .append(" | ").append(fmt(s.recallAt20()))
                    .append(" | ").append(fmt(s.recallAfterRerank()))
                    .append(" | ").append(fmt(s.parity()))
                    .append(" | ").append(fmt(s.precision()))
                    .append(" | ").append(s.injections())
                    .append(" | ").append(ms(s.latencyP50Ms()))
                    .append(" | ").append(ms(s.latencyP95Ms()))
                    .append(" | ").append(ms(s.meanTokens()))
                    .append(" | ").append(fmt(s.relevancePassRate()))
                    .append(" | ").append(fmt(s.faithfulnessPassRate()))
                    .append(" |\n");
        }
        md.append("\nMetrics are means over the cases in each row, excluding cases where a metric is undefined ")
                .append("(for example, parity applies to follow-ups only). See docs/rag-evaluation.md.\n");
        md.append("\n<details><summary>Per-case results</summary>\n\n");
        md.append("| Case | Recall@20 | Recall after rerank | Parity | Precision | Injections | ms | Tokens | Context | Error |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|---|---|\n");
        for (CaseResult r : report.cases()) {
            md.append("| ").append(r.id())
                    .append(" | ").append(fmt(r.recallAt20()))
                    .append(" | ").append(fmt(r.recallAfterRerank()))
                    .append(" | ").append(fmt(r.parity()))
                    .append(" | ").append(fmt(r.precision()))
                    .append(" | ").append(r.injections())
                    .append(" | ").append(ms(r.latencyMs()))
                    .append(" | ").append(r.tokens())
                    .append(" | ").append(String.join(", ", r.context()))
                    .append(" | ").append(r.error() == null ? "" : r.error().replace('|', '/'))
                    .append(" |\n");
        }
        md.append("\n</details>\n");
        return md.toString();
    }

    private static double mean(List<CaseResult> results, ToDoubleFunction<CaseResult> metric) {
        return RagMetrics.mean(results.stream().map(metric::applyAsDouble).toList());
    }

    private static double passRate(List<@Nullable Boolean> verdicts) {
        List<Boolean> judged = verdicts.stream().filter(Objects::nonNull).toList();
        if (judged.isEmpty()) {
            return Double.NaN;
        }
        return (double) judged.stream().filter(Boolean::booleanValue).count() / judged.size();
    }

    private static String fmt(double value) {
        return Double.isNaN(value) ? "n/a" : String.format(Locale.ROOT, "%.3f", value);
    }

    private static String ms(double value) {
        return Double.isNaN(value) ? "n/a" : String.format(Locale.ROOT, "%.0f", value);
    }
}
