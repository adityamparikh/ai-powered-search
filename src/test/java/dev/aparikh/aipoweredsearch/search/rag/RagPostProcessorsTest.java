package dev.aparikh.aipoweredsearch.search.rag;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W4: the post-processor chain's order and wrappers, the rerank short-circuit, and fail-open screening.
 */
class RagPostProcessorsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    /** Named so the observation tag can be asserted. */
    static final class FakeJevFilter implements DocumentPostProcessor {
        final AtomicReference<Query> seen = new AtomicReference<>();

        @Override
        public List<Document> process(Query query, List<Document> documents) {
            seen.set(query);
            return documents.stream().filter(d -> !d.getId().startsWith("inj-")).toList();
        }
    }

    /** A Jev filter whose API is down. A named class, because the chain names processors by class. */
    static final class UnavailableJevFilter implements DocumentPostProcessor {
        @Override
        public List<Document> process(Query query, List<Document> documents) {
            throw new IllegalStateException("TypeSafe API unavailable");
        }
    }

    static final class FakeReranker implements DocumentPostProcessor {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Query> seen = new AtomicReference<>();

        @Override
        public List<Document> process(Query query, List<Document> documents) {
            calls.incrementAndGet();
            seen.set(query);
            return documents.reversed().subList(0, Math.min(2, documents.size()));
        }
    }

    private static List<Document> docs(String... ids) {
        return java.util.Arrays.stream(ids).map(id -> new Document(id, "text " + id, Map.of())).toList();
    }

    private static List<String> names(RagPostProcessors chain) {
        return chain.processors().stream()
                .map(p -> ((ObservedDocumentPostProcessor) p).processorName())
                .toList();
    }

    private static List<String> ids(List<Document> documents) {
        return documents.stream().map(Document::getId).toList();
    }

    @Test
    void orderForEveryCombination() {
        FakeJevFilter filter = new FakeJevFilter();
        FakeReranker reranker = new FakeReranker();
        ObservationRegistry noop = ObservationRegistry.NOOP;

        assertThat(names(RagPostProcessors.assemble(null, TIMEOUT, null, 5, false, noop))).isEmpty();
        assertThat(names(RagPostProcessors.assemble(null, TIMEOUT, reranker, 5, false, noop)))
                .containsExactly("FakeReranker");
        assertThat(names(RagPostProcessors.assemble(filter, TIMEOUT, null, 5, true, noop)))
                .containsExactly("FakeJevFilter");
        assertThat(names(RagPostProcessors.assemble(filter, TIMEOUT, reranker, 5, true, noop)))
                .containsExactly("FakeJevFilter", "FakeReranker");
    }

    @Test
    void filterRunsFirstAndBothStagesJudgeTheStandaloneQuery() {
        FakeJevFilter filter = new FakeJevFilter();
        FakeReranker reranker = new FakeReranker();
        RagPostProcessors chain = RagPostProcessors.assemble(filter, TIMEOUT, reranker, 1, true, ObservationRegistry.NOOP);
        Query followUp = Query.builder().text("Anything cheaper by the same author?")
                .context(Map.of(RagContextKeys.STANDALONE, "Books by George R.R. Martin cheaper than A Game of Thrones"))
                .build();

        List<Document> result = docs("grrm-02", "inj-04", "grrm-04", "grrm-07");
        for (DocumentPostProcessor stage : chain.processors()) {
            result = stage.process(followUp, result);
        }

        assertThat(filter.seen.get().text()).isEqualTo("Books by George R.R. Martin cheaper than A Game of Thrones");
        assertThat(reranker.seen.get().text()).isEqualTo("Books by George R.R. Martin cheaper than A Game of Thrones");
        assertThat(ids(result)).containsExactly("grrm-07", "grrm-04").doesNotContain("inj-04");
    }

    @Test
    void shortCircuitSkipsTheRerankerWithinTopK() {
        FakeReranker reranker = new FakeReranker();
        RerankShortCircuit shortCircuit = new RerankShortCircuit(reranker, 5);

        List<Document> few = docs("a", "b", "c", "d", "e");
        assertThat(shortCircuit.process(new Query("q"), few)).isSameAs(few);
        assertThat(reranker.calls).hasValue(0);

        List<String> many = ids(shortCircuit.process(new Query("q"), docs("a", "b", "c", "d", "e", "f")));
        assertThat(reranker.calls).hasValue(1);
        assertThat(many).containsExactly("f", "e");
        assertThat(shortCircuit.delegate()).isSameAs(reranker);
        assertThatThrownBy(() -> new RerankShortCircuit(reranker, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withoutShortCircuitTheRerankerAlwaysRuns() {
        FakeReranker reranker = new FakeReranker();
        RagPostProcessors chain = RagPostProcessors.assemble(null, TIMEOUT, reranker, 5, false, ObservationRegistry.NOOP);

        chain.processors().getFirst().process(new Query("q"), docs("a", "b"));

        assertThat(reranker.calls).hasValue(1);
    }

    @Test
    void screeningFailsOpenOnAnException() {
        DocumentPostProcessor failing = (q, d) -> {
            throw new IllegalStateException("TypeSafe API unavailable");
        };
        List<Document> input = docs("a", "b");

        assertThat(new FailOpenPostProcessor(failing, TIMEOUT, "jev-filter").process(new Query("q"), input))
                .isSameAs(input);
    }

    @Test
    void screeningFailsOpenOnATimeoutWithoutWaitingForTheDelegate() {
        DocumentPostProcessor slow = (q, d) -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        };
        List<Document> input = docs(IntStream.range(0, 20).mapToObj(i -> "d" + i).toArray(String[]::new));

        long start = System.nanoTime();
        List<Document> result = new FailOpenPostProcessor(slow, Duration.ofMillis(200), "jev-filter").process(new Query("q"), input);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result).isSameAs(input);
        assertThat(elapsedMs).isLessThan(5_000);
    }

    @Test
    void screeningPassesResultsThroughWhenTheDelegateAnswers() {
        FailOpenPostProcessor guard = new FailOpenPostProcessor(new FakeJevFilter(), TIMEOUT, "jev-filter");

        assertThat(ids(guard.process(new Query("q"), docs("a", "inj-01", "b")))).containsExactly("a", "b");
        assertThat(guard.process(new Query("q"), List.of())).isEmpty();
        assertThat(guard.delegate()).isInstanceOf(FakeJevFilter.class);
    }

    /** A registry that records "<observation>.<event>" for every observation event. */
    private static ObservationRegistry recordingEvents(List<String> events) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onEvent(Observation.Event event, Observation.Context context) {
                events.add(context.getName() + "." + event.getName());
            }
        });
        return registry;
    }

    @Test
    void failingOpenIsCountedOnThePostprocessObservation() {
        List<String> events = new CopyOnWriteArrayList<>();
        RagPostProcessors chain = RagPostProcessors.assemble(new UnavailableJevFilter(), TIMEOUT, null, 5, false, recordingEvents(events));
        List<Document> input = docs("a", "b");

        assertThat(chain.processors().getFirst().process(new Query("q"), input)).isSameAs(input);
        assertThat(events).containsExactly("rag.postprocess.fail.open");
    }

    @Test
    void aFilterThatDiscardsEverythingLeavesNoContextAndIsCounted() {
        List<String> events = new CopyOnWriteArrayList<>();
        RagPostProcessors chain = RagPostProcessors.assemble(new FakeJevFilter(), TIMEOUT, null, 5, false,
                recordingEvents(events));

        assertThat(chain.processors().getFirst().process(new Query("q"), docs("inj-01", "inj-02"))).isEmpty();
        assertThat(events).containsExactly("rag.postprocess.discarded.all");
    }
}
