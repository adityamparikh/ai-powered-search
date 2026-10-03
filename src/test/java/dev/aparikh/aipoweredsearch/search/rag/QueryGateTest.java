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
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.rag.Query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            one two three four five six                                       | false | true
            one two three four five six seven                                 | false | false
            (same)                                                            | false | false
            ...it...                                                          | false | false
            it/them                                                           | false | true
            same-author                                                       | false | true
            novels after 2000                                                 | false | true
            推荐一部有政治阴谋的史诗奇幻系列小说                                  | false | true
            """)
    void decidesFromHistoryLengthAndMarkers(String question, boolean history, boolean skip) {
        assertThat(gate(true).wouldSkip(question, history)).isEqualTo(skip);
    }

    @Test
    void edgeTrimmingKeepsTheBehaviourOfTheRegexItReplaced() {
        // Expected values are what the former ^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$ replaceAll produced,
        // replaced by a linear scan for SonarQube java:S5850 and java:S8786.
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("", "");
        expected.put("same?", "same");
        expected.put("\"same\"", "same");
        expected.put("(it)", "it");
        expected.put("?!", "");
        expected.put("...it...", "it");
        expected.put("it/them", "it/them");
        expected.put("same-author", "same-author");
        expected.put("$9", "9");
        expected.put("2000s,", "2000s");
        expected.put("\u00bfm\u00e1s?", "m\u00e1s");
        expected.put("e\u0301", "e");
        expected.put("\u2167.", "\u2167");
        expected.put("\u00bd", "\u00bd");
        expected.put("\u0663\u0664", "\u0663\u0664");
        expected.put("\u300a\u4e09\u4f53\u300b", "\u4e09\u4f53");
        expected.put("\uD835\uDC00!", "\uD835\uDC00");
        expected.put("-".repeat(1_000) + "x" + "-".repeat(1_000), "x");

        expected.forEach((token, word) ->
                assertThat(QueryGate.trimToLettersAndNumbers(token)).as(token).isEqualTo(word));
    }

    @Test
    void constraintMarkersSendConstraintQuestionsToThePlanner() {
        List<String> markers = new ArrayList<>(QueryGate.DEFAULT_MARKERS);
        markers.addAll(QueryGate.CONSTRAINT_MARKERS);
        QueryGate withConstraints = new QueryGate(true, 6, markers, meters);

        assertThat(withConstraints.wouldSkip("novels after 2000", false)).isFalse();
        assertThat(withConstraints.wouldSkip("A Clash of Kings", false)).isTrue();
    }

    @Test
    void blankMarkersForceNothing() {
        QueryGate noMarkers = new QueryGate(true, 6, List.of(" ", ""), meters);

        assertThat(noMarkers.wouldSkip("Is it any good?", false)).isTrue();
    }

    @Test
    void maxTokensBelowOneIsRejected() {
        assertThatThrownBy(() -> new QueryGate(true, 0, QueryGate.DEFAULT_MARKERS, meters))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-tokens");
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
    void earlierTurnsAreCountedBeyondTheHistoryCapAndWithoutSystemMessages() {
        AtomicInteger plannerCalls = new AtomicInteger();
        ChatModel planner = prompt -> {
            plannerCalls.incrementAndGet();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "{\"standalone\": \"s\", \"variants\": [], \"filters\": []}"))));
        };
        QueryPlanningExpander expander = QueryPlanningExpander.builder()
                .plannerChatClient(ChatClient.builder(planner).build())
                .systemPrompt("planner").collection("books").variants(0).historyMessages(0)
                .gate(gate(true))
                .build();
        // Short and marker-free, so only the history makes it a follow-up, even with history-messages=0.
        Query followUp = Query.builder().text("by Tolkien?")
                .history(new UserMessage("Recommend an epic fantasy series."), new UserMessage("by Tolkien?"))
                .context(new HashMap<>()).build();
        Query assistantOnly = Query.builder().text("by Tolkien?")
                .history(new AssistantMessage("Hello, what are you looking for?"), new UserMessage("by Tolkien?"))
                .context(new HashMap<>()).build();
        Query systemOnly = Query.builder().text("by Tolkien?")
                .history(new SystemMessage("You answer questions about books."), new UserMessage("by Tolkien?"))
                .context(new HashMap<>()).build();

        expander.expand(followUp);
        assertThat(plannerCalls).hasValue(1);
        expander.expand(assistantOnly);
        assertThat(plannerCalls).hasValue(2);
        assertThat(expander.expand(systemOnly)).containsExactly(systemOnly);
        assertThat(plannerCalls).hasValue(2);
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
