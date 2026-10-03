package dev.aparikh.aipoweredsearch.search.rag;

import dev.aparikh.aipoweredsearch.search.SearchRepository;
import dev.aparikh.aipoweredsearch.search.model.FieldInfo;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.rag.Query;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link QueryPlanningExpander} with a stub chat model (no network).
 */
class QueryPlanningExpanderTest {

    private static final String TURN_1 = "Recommend an epic fantasy series with political intrigue.";
    private static final String ANSWER_1 = "Try A Game of Thrones by George R.R. Martin, $9.99.";
    private static final String TURN_2 = "Anything cheaper by the same author?";
    private static final String STANDALONE = "Books by George R.R. Martin cheaper than A Game of Thrones";

    private static final String PLAN_JSON = """
            {"standalone": "Books by George R.R. Martin cheaper than A Game of Thrones",
             "keywordQuery": "George R.R. Martin",
             "variants": ["Cheaper novels from the author of A Song of Ice and Fire",
                          "Lower-priced George R.R. Martin paperbacks"],
             "hydePassage": "A sweeping tale of rival houses.",
             "filters": ["metadata_author:\\"George R.R. Martin\\"", "metadata_price:[* TO 9.98]", "{!func}div(1,0)"]}
            """;

    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final TestObservationRegistry observations = TestObservationRegistry.create();

    /** A chat model that records each prompt and answers with {@code reply}. */
    private ChatModel replying(Function<Prompt, String> reply) {
        return prompt -> {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply.apply(prompt)))));
        };
    }

    private FilterValidator validator() {
        SearchRepository repository = mock(SearchRepository.class);
        when(repository.getFieldsWithSchema("books")).thenReturn(List.of(
                new FieldInfo("metadata_author", "strings", true, true, true, true),
                new FieldInfo("metadata_price", "pdouble", false, true, true, true)));
        return new FilterValidator(repository, Duration.ofMinutes(5));
    }

    private QueryPlanningExpander expander(ChatModel model, int variants, FilterValidator validator) {
        return QueryPlanningExpander.builder()
                .plannerChatClient(ChatClient.builder(model).build())
                .systemPrompt("You are the query planner.")
                .filterValidator(validator)
                .collection("books")
                .variants(variants)
                .timeout(Duration.ofMillis(500))
                .observationRegistry(observations)
                .build();
    }

    private static Query followUp() {
        List<Message> history = List.of(new SystemMessage("system"), new UserMessage(TURN_1),
                new AssistantMessage(ANSWER_1), new UserMessage(TURN_2));
        return Query.builder().text(TURN_2).history(history)
                .context(new HashMap<>(Map.of(ChatMemory.CONVERSATION_ID, "c-1"))).build();
    }

    @Test
    void producesStandaloneThenVariantsWithContextKeys() {
        List<Query> queries = expander(replying(p -> PLAN_JSON), 2, validator()).expand(followUp());

        assertThat(queries).extracting(Query::text).containsExactly(
                STANDALONE,
                "Cheaper novels from the author of A Song of Ice and Fire",
                "Lower-priced George R.R. Martin paperbacks");

        Query standalone = queries.getFirst();
        assertThat(standalone.context())
                .containsEntry(RagContextKeys.STANDALONE, STANDALONE)
                .containsEntry(RagContextKeys.KEYWORD_QUERY, "George R.R. Martin")
                .containsEntry(ChatMemory.CONVERSATION_ID, "c-1");
        // The {!func} clause never survives validation.
        assertThat(standalone.context().get(RagContextKeys.FILTERS)).isEqualTo(List.of(
                "metadata_author:\"George R.R. Martin\"", "metadata_price:[* TO 9.98]"));

        Query variant = queries.get(1);
        assertThat(variant.context())
                .containsEntry(RagContextKeys.STANDALONE, STANDALONE)
                .containsEntry(RagContextKeys.KEYWORD_QUERY, variant.text())
                .containsKey(RagContextKeys.FILTERS);
        assertThat(variant.history()).isEqualTo(followUp().history());
    }

    @Test
    void handsTheStandaloneQueryToPostProcessorsThroughTheOriginalContext() {
        Query original = followUp();

        expander(replying(p -> PLAN_JSON), 2, validator()).expand(original);

        assertThat(original.context()).containsEntry(RagContextKeys.STANDALONE, STANDALONE);
    }

    @Test
    void passesTheConversationAndFieldsToThePlanner() {
        expander(replying(p -> PLAN_JSON), 2, validator()).expand(followUp());

        Prompt prompt = prompts.getFirst();
        String system = prompt.getSystemMessage().getText();
        String user = prompt.getUserMessage().getText();
        assertThat(system).contains("You are the query planner.");
        assertThat(user)
                .contains("USER: " + TURN_1)
                .contains("ASSISTANT: " + ANSWER_1)
                .contains("Latest user message:\n" + TURN_2)
                .contains("Number of variants: 2")
                .contains("- metadata_author: strings")
                .contains("- metadata_price: pdouble")
                // the current question appears once, as the latest message, not as history
                .doesNotContain("USER: " + TURN_2)
                .doesNotContain("system");
    }

    @Test
    void recordsAPlanObservation() {
        expander(replying(p -> PLAN_JSON), 2, validator()).expand(followUp());

        assertThat(observations).hasObservationWithNameEqualTo(RagObservations.PLAN).that().hasBeenStopped();
    }

    @Test
    void withFiltersDisabledThePlannerIsToldThereAreNoFieldsAndNoFiltersAreSet() {
        QueryPlanningExpander expander = QueryPlanningExpander.builder()
                .plannerChatClient(ChatClient.builder(replying(p -> PLAN_JSON)).build())
                .systemPrompt("planner").collection("books").variants(2).build();

        List<Query> queries = expander.expand(followUp());

        assertThat(prompts.getFirst().getUserMessage().getText()).contains("Filterable fields: none");
        assertThat(queries).allSatisfy(q -> assertThat(q.context()).doesNotContainKey(RagContextKeys.FILTERS));
    }

    // ==================== fallbacks: always the original query ====================

    @Test
    void fallsBackOnTimeoutWithoutWaitingForThePlanner() {
        ChatModel slow = replying(p -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return PLAN_JSON;
        });
        Query original = followUp();

        long start = System.nanoTime();
        List<Query> queries = expander(slow, 2, validator()).expand(original);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(queries).containsExactly(original);
        assertThat(elapsedMs).isLessThan(3_000);
    }

    @Test
    void fallsBackWhenThePlannerThrows() {
        ChatModel failing = prompt -> {
            throw new IllegalStateException("model not found: claude-nonexistent");
        };
        Query original = followUp();

        assertThat(expander(failing, 2, validator()).expand(original)).containsExactly(original);
    }

    @Test
    void fallsBackOnUnparseableOutput() {
        Query original = followUp();

        assertThat(expander(replying(p -> "Sure! Here are some books you might like."), 2, validator())
                .expand(original)).containsExactly(original);
    }

    @Test
    void fallsBackOnABlankStandalone() {
        String plan = "{\"standalone\": \"  \", \"keywordQuery\": \"x\", \"variants\": [\"a\", \"b\"], \"filters\": []}";
        Query original = followUp();

        assertThat(expander(replying(p -> plan), 2, validator()).expand(original)).containsExactly(original);
        assertThat(original.context()).doesNotContainKey(RagContextKeys.STANDALONE);
    }

    @Test
    void fallsBackOnTheWrongNumberOfVariants() {
        String plan = "{\"standalone\": \"s\", \"keywordQuery\": \"k\", \"variants\": [\"only one\", \"  \"], \"filters\": []}";
        Query original = followUp();

        assertThat(expander(replying(p -> plan), 2, validator()).expand(original)).containsExactly(original);
    }

    @Test
    void fallsBackOnANullPlan() {
        Query original = followUp();

        assertThat(expander(replying(p -> "null"), 2, validator()).expand(original)).containsExactly(original);
    }

    // ==================== edge cases ====================

    @Test
    void deduplicatesVariantsThatEchoTheStandaloneOrEachOther() {
        // RetrievalAugmentationAdvisor collects queries with Collectors.toMap: equal queries
        // would be a duplicate key and a 5xx (W0 finding A1).
        String plan = """
                {"standalone": "Books by George R.R. Martin",
                 "keywordQuery": "George R.R. Martin",
                 "variants": ["books by george r.r. martin?", "Other George R.R. Martin novels", "  other  george r.r. martin novels "],
                 "filters": []}
                """;

        List<Query> queries = expander(replying(p -> plan), 3, validator()).expand(followUp());

        assertThat(queries).extracting(Query::text)
                .containsExactly("Books by George R.R. Martin", "Other George R.R. Martin novels");
    }

    @Test
    void zeroVariantsYieldsOnlyTheStandaloneQuery() {
        String plan = "{\"standalone\": \"s\", \"keywordQuery\": \"k\", \"variants\": [], \"filters\": []}";

        assertThat(expander(replying(p -> plan), 0, validator()).expand(followUp()))
                .extracting(Query::text).containsExactly("s");
    }

    @Test
    void aReadOnlyOriginalContextIsLeftAloneWithoutFailing() {
        Query readOnly = Query.builder().text(TURN_2).context(Map.of("k", "v")).build();

        List<Query> queries = expander(replying(p -> PLAN_JSON), 2, validator()).expand(readOnly);

        assertThat(queries).hasSize(3);
        assertThat(queries.getFirst().context()).containsEntry("k", "v").containsKey(RagContextKeys.STANDALONE);
    }

    @Test
    void missingKeywordQueryFallsBackToTheStandaloneText() {
        String plan = "{\"standalone\": \"s\", \"variants\": [], \"filters\": null}";

        Query query = expander(replying(p -> plan), 0, validator()).expand(followUp()).getFirst();

        assertThat(query.context()).containsEntry(RagContextKeys.KEYWORD_QUERY, "s").doesNotContainKey(RagContextKeys.FILTERS);
    }

    @Test
    void historyIsLimitedToTheMostRecentMessagesAndLongMessagesAreTruncated() {
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            history.add(new UserMessage("question " + i));
            history.add(new AssistantMessage("answer " + i + " " + "x".repeat(3_000)));
        }
        history.add(new UserMessage("latest"));
        QueryPlanningExpander expander = QueryPlanningExpander.builder()
                .plannerChatClient(ChatClient.builder(replying(p -> PLAN_JSON)).build())
                .systemPrompt("planner").collection("books").historyMessages(4).build();
        Query query = Query.builder().text("latest").history(history).build();

        List<Message> turns = expander.priorTurns(query);
        String message = expander.userMessage(query);

        assertThat(turns).hasSize(4).extracting(Message::getText).first().isEqualTo("question 18");
        assertThat(message).doesNotContain("question 17").contains("question 19").contains("…");
        assertThat(message.length()).isLessThan(4 * QueryPlanningExpander.MAX_HISTORY_MESSAGE_CHARS + 1_000);
    }

    @Test
    void firstTurnHasNoHistory() {
        QueryPlanningExpander expander = expander(replying(p -> PLAN_JSON), 2, validator());
        Query first = Query.builder().text("A Clash of Kings").history(new UserMessage("A Clash of Kings")).build();

        assertThat(expander.priorTurns(first)).isEmpty();
        assertThat(expander.userMessage(first)).contains("Conversation so far:\n(none)");
    }

    @Test
    void normalisationIgnoresCaseWhitespaceAndTrailingPunctuation() {
        assertThat(QueryPlanningExpander.normalise("  Books  by\tMartin?! "))
                .isEqualTo(QueryPlanningExpander.normalise("books by martin"));
    }
}
