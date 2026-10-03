package dev.aparikh.aipoweredsearch.evaluation;

import dev.aparikh.aipoweredsearch.config.EvaluationModelsTestConfiguration;
import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import dev.aparikh.aipoweredsearch.indexing.IndexService;
import dev.aparikh.aipoweredsearch.search.SearchService;
import dev.aparikh.aipoweredsearch.search.model.AskRequest;
import dev.aparikh.aipoweredsearch.search.model.AskResponse;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evaluation harness for {@code /api/v1/search/ask} (epic #32, W0).
 *
 * <p>Indexes {@code eval/books-fixture.json} through the production indexing path into a
 * collection built from the project configset, then replays every case in
 * {@code eval/rag-eval-set.json} through {@link SearchService#ask}, the method behind
 * {@code /ask}. Per category it reports recall@20 after fusion, follow-up parity, context
 * precision, injection pass-through, latency, tokens and (optionally) answer relevance and
 * faithfulness. The results go to {@code build/reports/rag-eval/}.</p>
 *
 * <p>System properties (forwarded by Gradle):</p>
 * <ul>
 *   <li>{@code rag.eval.props}: {@code ;}-separated Spring properties for the app under test,
 *       e.g. {@code search.rag.planner.enabled=true;search.rag.hyde.enabled=true}. This is how
 *       the same harness runs with stages on or off.</li>
 *   <li>{@code rag.eval.label}: report label (default {@code baseline} when no props are set,
 *       else {@code candidate}).</li>
 *   <li>{@code rag.eval.judge}: {@code false} skips the Ollama judge.</li>
 *   <li>{@code rag.eval.ollama-url}: use this Ollama server for the judge instead of a container.</li>
 *   <li>{@code rag.eval.cases}: comma-separated case ids or categories to run a subset.</li>
 * </ul>
 *
 * <p>Tagged {@code rag-eval} and excluded from a plain {@code ./gradlew build}: it makes real,
 * billed model calls. Run it with {@code ./gradlew test --tests RagEvaluationIT}. Without
 * {@code ANTHROPIC_API_KEY} and {@code OPENAI_API_KEY} it is skipped.</p>
 */
@Tag("rag-eval")
@SpringBootTest
@Import({PostgresTestConfiguration.class, SolrTestConfiguration.class,
        EvaluationModelsTestConfiguration.class, RagEvalTestConfiguration.class})
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+"),
        @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
})
class RagEvaluationIT extends EvaluationTestBase {

    private static final Logger log = LoggerFactory.getLogger(RagEvaluationIT.class);

    static final String COLLECTION = "rag-eval";
    static final int RECALL_CUTOFF = 20;
    static final Path REPORT_DIR = Path.of("build", "reports", "rag-eval");

    @Autowired
    private IndexService indexService;

    @Autowired
    private SearchService searchService;

    @Autowired
    private CandidateRecorder candidateRecorder;

    @Autowired
    private ChatModel chatModel;

    private final RagEvalData.BooksFixture fixture = RagEvalData.fixture();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("solr.default.collection", () -> COLLECTION);
        flags().forEach((key, value) -> registry.add(key, () -> value));
    }

    /** Parses {@code rag.eval.props} into Spring properties for the app under test. */
    static Map<String, String> flags() {
        String raw = System.getProperty("rag.eval.props", "");
        Map<String, String> flags = new LinkedHashMap<>();
        for (String pair : raw.split(";")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("rag.eval.props entries must be key=value, got: " + pair);
            }
            flags.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return flags;
    }

    @Override
    protected boolean judgeEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty("rag.eval.judge", "true"));
    }

    @Override
    protected void createBooksCollection() throws Exception {
        RagEvalFixture.uploadConfigSet(solr);
        RagEvalFixture.createCollection(solrClient, COLLECTION);
    }

    @Override
    protected void loadBooks() throws Exception {
        RagEvalFixture.index(indexService, solrClient, COLLECTION, fixture.books());
    }

    @Test
    void evaluate() throws Exception {
        UsageRecordingChatModel usage = (UsageRecordingChatModel) chatModel;
        Map<String, RagEvalData.Book> books = fixture.books().stream()
                .collect(Collectors.toMap(RagEvalData.Book::id, Function.identity()));
        String runId = UUID.randomUUID().toString().substring(0, 8);

        List<RagEvalReport.CaseResult> results = new ArrayList<>();
        for (RagEvalData.EvalCase evalCase : selectedCases()) {
            RagEvalReport.CaseResult result = runCase(evalCase, runId, usage, books);
            log.info("[rag-eval] {} recall@20={} parity={} precision={} injections={} {}ms",
                    result.id(), result.recallAt20(), result.parity(), result.precision(),
                    result.injections(), Math.round(result.latencyMs()));
            results.add(result);
        }

        Map<String, String> flags = flags();
        String label = System.getProperty("rag.eval.label", flags.isEmpty() ? "baseline" : "candidate");
        RagEvalReport.Report report = RagEvalReport.build(label, gitSha(), flags, judgeEnabled(), results);
        RagEvalReport.write(report, REPORT_DIR);
        log.info("[rag-eval] report written to {}\n{}", REPORT_DIR.toAbsolutePath(), RagEvalReport.toMarkdown(report));

        // The harness measures; it does not gate. It only fails if it could not measure at all.
        assertThat(results).isNotEmpty();
        assertThat(results.stream().filter(r -> r.error() == null)).as("cases that ran without error").isNotEmpty();
    }

    private List<RagEvalData.EvalCase> selectedCases() {
        List<RagEvalData.EvalCase> all = RagEvalData.evalSet().cases();
        String filter = System.getProperty("rag.eval.cases", "");
        if (filter.isBlank()) {
            return all;
        }
        Set<String> wanted = Set.of(filter.split(","));
        return all.stream().filter(c -> wanted.contains(c.id()) || wanted.contains(c.category())).toList();
    }

    private RagEvalReport.CaseResult runCase(RagEvalData.EvalCase evalCase, String runId,
                                             UsageRecordingChatModel usage, Map<String, RagEvalData.Book> books) {
        String conversationId = "eval-" + evalCase.id() + "-" + runId;
        Set<String> relevant = new HashSet<>(evalCase.relevantIds());
        try {
            // Earlier turns build up chat memory; metrics are taken on the final turn only.
            for (String turn : evalCase.turns().subList(0, evalCase.turns().size() - 1)) {
                searchService.ask(new AskRequest(turn, conversationId));
            }

            usage.reset();
            long start = System.nanoTime();
            AskResponse response = searchService.ask(new AskRequest(evalCase.lastTurn(), conversationId));
            double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
            UsageRecordingChatModel.Totals tokens = usage.snapshot();

            List<String> candidates = candidateRecorder.lastCandidates(conversationId);
            List<String> context = response.sources();

            double parity = Double.NaN;
            if (evalCase.isFollowUp() && evalCase.standalone() != null) {
                String standaloneConversation = conversationId + "-standalone";
                searchService.ask(new AskRequest(evalCase.standalone(), standaloneConversation));
                parity = RagMetrics.parity(candidates, candidateRecorder.lastCandidates(standaloneConversation));
            }

            Boolean relevantVerdict = null;
            Boolean faithfulVerdict = null;
            String answer = response.answer();
            if (judgeEnabled() && answer != null && !answer.isBlank()) {
                List<Document> contextDocuments = context.stream()
                        .map(books::get)
                        .filter(Objects::nonNull)
                        .map(book -> new Document(book.id(), book.content(), Map.of()))
                        .toList();
                String question = evalCase.standalone() != null ? evalCase.standalone() : evalCase.lastTurn();
                relevantVerdict = judge(() -> Objects.requireNonNull(relevancyEvaluator)
                        .evaluate(new EvaluationRequest(question, contextDocuments, answer)).isPass());
                if (!contextDocuments.isEmpty()) {
                    faithfulVerdict = judge(() -> Objects.requireNonNull(factCheckingEvaluator)
                            .evaluate(new EvaluationRequest(contextDocuments, answer)).isPass());
                }
            }

            return new RagEvalReport.CaseResult(evalCase.id(), evalCase.category(),
                    RagMetrics.recallAtK(candidates, relevant, RECALL_CUTOFF),
                    parity,
                    RagMetrics.precision(context, relevant),
                    RagMetrics.injectionCount(context),
                    latencyMs, tokens.totalTokens(), tokens.calls(),
                    relevantVerdict, faithfulVerdict, candidates, context, null);
        } catch (Exception e) {
            log.warn("[rag-eval] case {} failed: {}", evalCase.id(), e.toString());
            return new RagEvalReport.CaseResult(evalCase.id(), evalCase.category(), Double.NaN, Double.NaN,
                    Double.NaN, 0, Double.NaN, 0, 0, null, null, List.of(), List.of(), e.toString());
        }
    }

    private static @Nullable Boolean judge(java.util.concurrent.Callable<Boolean> verdict) {
        try {
            return verdict.call();
        } catch (Exception e) {
            log.warn("[rag-eval] judge call failed: {}", e.toString());
            return null;
        }
    }

    private static String gitSha() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String sha = reader.readLine();
                return process.waitFor() == 0 && sha != null ? sha.trim() : "unknown";
            }
        } catch (Exception e) {
            return "unknown";
        }
    }
}
