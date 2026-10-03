package dev.aparikh.aipoweredsearch.search.rag;

import dev.aparikh.aipoweredsearch.evaluation.RagEvalData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.rag.Query;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W6: which questions skip the planner, the {@code rag.gate} counter, and the gate's hit rate on
 * the evaluation set.
 */
class QueryGateTest {

    private static final Logger log = LoggerFactory.getLogger(QueryGateTest.class);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private QueryGate gate(boolean enabled) {
        return new QueryGate(enabled, 6, QueryGate.DEFAULT_MARKERS, meters);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" history={1} -> skip={2}")
    @CsvSource(delimiter = '|', textBlock = """
            A Clash of Kings                                                  | false | true
            Neuromancer                                                       | false | true
            Hyperion by Dan Simmons                                           | false | true
            Anything cheaper by the same author?                              | true  | false
            Anything cheaper by the same author?                              | false | false
            A Clash of Kings                                                  | true  | false
            Recommend an epic fantasy series with political intrigue please   | false | false
            Is it any good?                                                   | false | false
            It’s good                                                         | false | false
            SAME author                                                       | false | false
            another one                                                       | false | false
            books like those                                                  | false | false
            '   '                                                             | false | false
            """)
    void decidesFromHistoryLengthAndMarkers(String question, boolean history, boolean skip) {
        assertThat(gate(true).wouldSkip(question, history)).isEqualTo(skip);
    }

    @Test
    void disabledGatePlansEverything() {
        assertThat(gate(false).wouldSkip("A Clash of Kings", false)).isFalse();
    }

    @Test
    void countsEveryDecisionByOutcome() {
        QueryGate gate = gate(true);

        gate.skipPlanning("A Clash of Kings", false);
        gate.skipPlanning("Neuromancer", false);
        gate.skipPlanning("Anything cheaper by the same author?", true);

        assertThat(meters.get(QueryGate.METRIC).tag(QueryGate.OUTCOME_TAG, QueryGate.SKIPPED).counter().count()).isEqualTo(2);
        assertThat(meters.get(QueryGate.METRIC).tag(QueryGate.OUTCOME_TAG, QueryGate.PLANNED).counter().count()).isEqualTo(1);
    }

    @Test
    void markersAndLengthAreConfigurable() {
        QueryGate strict = new QueryGate(true, 2, List.of(" Clash "), meters);

        assertThat(strict.wouldSkip("Neuromancer", false)).isTrue();
        assertThat(strict.wouldSkip("A Clash of Kings", false)).isFalse();
        assertThat(strict.wouldSkip("The Fifth Season", false)).isFalse();
    }

    @Test
    void aGatedQuestionNeverReachesThePlanner() {
        AtomicInteger plannerCalls = new AtomicInteger();
        ChatModel planner = prompt -> {
            plannerCalls.incrementAndGet();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "{\"standalone\": \"s\", \"variants\": [], \"filters\": []}"))));
        };
        QueryPlanningExpander expander = QueryPlanningExpander.builder()
                .plannerChatClient(ChatClient.builder(planner).build())
                .systemPrompt("planner").collection("books").variants(0)
                .gate(gate(true))
                .build();
        Query lookup = Query.builder().text("A Clash of Kings").history(new UserMessage("A Clash of Kings"))
                .context(new HashMap<>()).build();
        Query followUp = Query.builder().text("Anything cheaper by the same author?")
                .history(new UserMessage("Recommend an epic fantasy series."), new AssistantMessage("A Game of Thrones."),
                        new UserMessage("Anything cheaper by the same author?"))
                .context(new HashMap<>()).build();

        assertThat(expander.expand(lookup)).containsExactly(lookup);
        assertThat(plannerCalls).hasValue(0);
        assertThat(lookup.context()).doesNotContainKey(RagContextKeys.STANDALONE);

        expander.expand(followUp);
        assertThat(plannerCalls).hasValue(1);
    }

    @Test
    void hitRateOnTheEvaluationSetNeverGatesAFollowUp() {
        QueryGate gate = gate(true);
        Map<String, int[]> byCategory = new LinkedHashMap<>();
        for (RagEvalData.EvalCase evalCase : RagEvalData.evalSet().cases()) {
            boolean skip = gate.wouldSkip(evalCase.lastTurn(), evalCase.isFollowUp());
            int[] counts = byCategory.computeIfAbsent(evalCase.category(), c -> new int[2]);
            counts[0] += skip ? 1 : 0;
            counts[1]++;
        }
        int skipped = byCategory.values().stream().mapToInt(c -> c[0]).sum();
        int total = byCategory.values().stream().mapToInt(c -> c[1]).sum();
        byCategory.forEach((category, c) -> log.info("[gate] {}: {}/{} skipped", category, c[0], c[1]));
        log.info("[gate] overall hit rate: {}/{} = {}", skipped, total, (double) skipped / total);

        // A gated follow-up would retrieve on its context-free text: never acceptable.
        assertThat(byCategory.get("follow-up")[0]).isZero();
        // Every plain title lookup in the set is short and marker-free, so all of them skip.
        assertThat(byCategory.get("keyword")[0]).isEqualTo(byCategory.get("keyword")[1]);
    }
}
