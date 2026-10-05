package dev.aparikh.aipoweredsearch.search.rag;

import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Plans retrieval for a RAG turn with one small-model call (W1, #36): it rewrites the question as
 * a standalone query, writes variant phrasings and a keyword query, and extracts filters.
 *
 * <p>Fixes three problems with retrieving on the raw question:</p>
 * <ul>
 *   <li><strong>Follow-ups</strong> (P1): "Anything cheaper by the same author?" has no referent.
 *       The planner reads the chat history ({@code Query.history()}, which holds earlier turns
 *       because the memory advisor runs first, W0 finding A1) and rewrites it, e.g. "Books by
 *       George R.R. Martin cheaper than A Game of Thrones".</li>
 *   <li><strong>Constraints</strong> (P2): explicit constraints become Solr filter queries,
 *       admitted only by {@link FilterValidator}, and only when filters are enabled.</li>
 *   <li><strong>One probe</strong> (P3): variants with different vocabulary ("court politics",
 *       "rival noble houses") each get their own retrieval. {@link RrfDocumentJoiner} fuses
 *       them all.</li>
 * </ul>
 *
 * <p><strong>Output.</strong> Index 0 is the standalone query; then one query per variant, after
 * de-duplication by normalised text. Equal queries would crash
 * {@code RetrievalAugmentationAdvisor}, which collects them with {@code Collectors.toMap}. Each
 * query carries its own copy of the context with {@link RagContextKeys#STANDALONE},
 * {@link RagContextKeys#KEYWORD_QUERY} and, when present, {@link RagContextKeys#FILTERS}.
 * {@link RagContextKeys#KEYWORD_QUERY} is read by the BM25 leg and the planner's HyDE passage by the
 * kNN leg (W2, see {@code LegRouting}).</p>
 *
 * <p><strong>Standalone hand-off.</strong> {@code RetrievalAugmentationAdvisor} passes the
 * <em>original</em> query to post-processors. The expander therefore also writes
 * {@link RagContextKeys#STANDALONE} into the original query's context, which is the advisor's own
 * mutable map, and {@link StandaloneQueryAwarePostProcessor} hands the standalone text to the
 * reranker. Expansion and post-processing both run on the caller thread, so no
 * {@code ThreadLocal} is involved (W0 finding A1).</p>
 *
 * <p><strong>Per-leg inputs (W2).</strong> With {@code search.rag.hyde.enabled}, the standalone
 * query also carries {@link RagContextKeys#VECTOR_TEXT} = the plan's HyDE passage. Every planned
 * query's kNN text is then embedded in one {@link EmbeddingBatcher} request, and the vector is
 * set as {@link RagContextKeys#VECTOR}.</p>
 *
 * <p><strong>Follow-ups only (W6).</strong> With {@code followUpsOnly}, a question with no earlier
 * turns in the conversation is returned unchanged without calling the planner. The planner's gain
 * is concentrated in follow-ups, whose text has a referent only in the history; a first question
 * is already self-contained, and planning it adds a model call for little benefit.</p>
 *
 * <p><strong>Fails safe.</strong> On a timeout, an exception, an unparseable reply or a blank
 * standalone query, the expander logs a WARN and returns {@code List.of(originalQuery)}. That is
 * exactly today's behaviour, as in Spring AI's {@code MultiQueryExpander}, and each such fallback
 * is recorded as an error on the {@value RagObservations#PLAN} observation. Unlike
 * {@code MultiQueryExpander}, a wrong variant count does not discard the plan: the standalone
 * rewrite is its most valuable part, so missing variants are tolerated and extras are dropped.</p>
 *
 * <p>Pattern: <a href="https://medium.com/@thetalkingapp/spring-ai-recipe-making-rag-conversation-aware-189b82a37060">Making
 * RAG Conversation-Aware</a> and
 * <a href="https://medium.com/@thetalkingapp/spring-ai-recipe-filtering-rag-results-with-metadata-bef2f8a7cb72">Filtering
 * RAG Results with Metadata</a>; multi-query expansion as in
 * <a href="https://github.com/spring-projects/spring-ai/blob/main/spring-ai-rag/src/main/java/org/springframework/ai/rag/preretrieval/query/expansion/MultiQueryExpander.java">MultiQueryExpander</a>.</p>
 */
public final class QueryPlanningExpander implements QueryExpander {

    private static final Logger log = LoggerFactory.getLogger(QueryPlanningExpander.class);

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** One character of what {@link #normalise} strips from the end: ASCII punctuation or whitespace. */
    private static final Pattern TRAILING_CHAR = Pattern.compile("[\\p{Punct}\\s]");

    /** Longest single history message passed to the planner; long answers are truncated. */
    static final int MAX_HISTORY_MESSAGE_CHARS = 1500;

    private final ChatClient plannerChatClient;
    private final String systemPrompt;
    private final @Nullable FilterValidator filterValidator;
    private final String collection;
    private final int variants;
    private final Duration timeout;
    private final int historyMessages;
    private final ObservationRegistry observationRegistry;
    private final boolean hydeEnabled;
    private final @Nullable EmbeddingBatcher embeddingBatcher;
    private final boolean followUpsOnly;
    private final ContextSnapshotFactory contextSnapshotFactory = ContextSnapshotFactory.builder().build();

    private QueryPlanningExpander(Builder builder) {
        this.plannerChatClient = Objects.requireNonNull(builder.plannerChatClient, "plannerChatClient");
        this.systemPrompt = Objects.requireNonNull(builder.systemPrompt, "systemPrompt");
        this.filterValidator = builder.filterValidator;
        this.collection = Objects.requireNonNull(builder.collection, "collection");
        if (builder.variants < 0) {
            throw new IllegalArgumentException("variants must not be negative, got: " + builder.variants);
        }
        this.variants = builder.variants;
        this.timeout = builder.timeout;
        this.historyMessages = builder.historyMessages;
        this.observationRegistry = builder.observationRegistry;
        this.hydeEnabled = builder.hydeEnabled;
        this.embeddingBatcher = builder.embeddingBatcher;
        this.followUpsOnly = builder.followUpsOnly;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public List<Query> expand(Query query) {
        // Follow-ups only (W6): a first question is retrieved exactly as with the planner off.
        // Earlier turns are counted before the history-messages cap, so history-messages=0 cannot
        // make a follow-up look like a first question.
        if (followUpsOnly && allPriorTurns(query).isEmpty()) {
            log.debug("First question in the conversation; retrieving '{}' without planning", query.text());
            return List.of(query);
        }

        QueryPlan accepted;
        try {
            // A rejected plan is thrown inside the observation, so every fallback is counted
            // as an errored rag.plan, not only timeouts and model errors.
            accepted = RagObservations.observe(observationRegistry, RagObservations.PLAN, () -> {
                QueryPlan plan = plan(query);
                String problem = problemWith(plan);
                if (problem != null) {
                    throw new IllegalStateException("plan rejected: " + problem);
                }
                return plan;
            });
        } catch (RuntimeException e) {
            log.warn("Query planning failed, retrieving with the original question: {}", describe(e));
            return List.of(query);
        }
        String standalone = Objects.requireNonNull(accepted.standalone()).strip();
        String keywordQuery = isBlank(accepted.keywordQuery()) ? standalone
                : Objects.requireNonNull(accepted.keywordQuery()).strip();
        List<String> filters = filterValidator == null ? List.of()
                : filterValidator.render(collection, accepted.filters());

        handOffStandalone(query, standalone);

        List<Query> queries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addQuery(queries, seen, query, standalone, keywordQuery, standalone, filters);
        List<String> variantTexts = nonBlank(accepted.variants());
        if (variantTexts.size() != variants) {
            log.debug("Planner returned {} variants, {} requested; using at most {}",
                    variantTexts.size(), variants, variants);
        }
        for (String variant : variantTexts.subList(0, Math.min(variants, variantTexts.size()))) {
            addQuery(queries, seen, query, variant.strip(), variant.strip(), standalone, filters);
        }

        // HyDE (W2): the standalone query's kNN leg searches with an imagined catalogue entry, so a
        // passage is compared with passages. Variants keep their own text for the vector leg, and
        // the BM25 leg never sees the passage (see LegRouting). Index 0 is always the standalone
        // query: it is added first, to an empty seen set, so de-duplication cannot drop it.
        String hydePassage = accepted.hydePassage();
        if (hydeEnabled && hydePassage != null && !hydePassage.isBlank()) {
            queries.getFirst().context().put(RagContextKeys.VECTOR_TEXT, hydePassage.strip());
        }

        // One embedding request for every kNN leg of this turn, instead of one per query (W2).
        if (embeddingBatcher != null) {
            embeddingBatcher.embed(queries);
        }

        log.debug("Planned {} queries for '{}' (standalone '{}', {} filters)",
                queries.size(), query.text(), standalone, filters.size());
        return queries;
    }

    /**
     * Calls the planner on a virtual thread bounded by {@code timeout}, cancelling it on expiry.
     * The user message is built inside the bounded call, so a cold field-introspection lookup
     * ({@link FilterValidator#filterableFields}) counts against the timeout too.
     */
    private QueryPlan plan(Query query) {
        Callable<Optional<QueryPlan>> call = () -> Optional.ofNullable(plannerChatClient.prompt()
                .system(systemPrompt)
                .user(userMessage(query))
                .call()
                .entity(QueryPlan.class));
        FutureTask<Optional<QueryPlan>> task = new FutureTask<>(contextSnapshotFactory.captureAll().wrap(call));
        Thread.ofVirtual().name("rag-query-planner").start(task);
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .orElseThrow(() -> new IllegalStateException("planner returned no plan"));
        } catch (TimeoutException e) {
            task.cancel(true);
            throw new IllegalStateException("planner timed out after " + timeout.toMillis() + " ms", e);
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while planning", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException(cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
        }
    }

    /**
     * The planner's user message: the conversation, the latest message, the variant count and,
     * when filters are enabled, the filterable fields with their types.
     */
    String userMessage(Query query) {
        StringBuilder message = new StringBuilder("Conversation so far:\n");
        List<Message> history = priorTurns(query);
        if (history.isEmpty()) {
            message.append("(none)\n");
        }
        for (Message turn : history) {
            message.append(turn.getMessageType() == MessageType.ASSISTANT ? "ASSISTANT: " : "USER: ")
                    .append(truncate(Objects.requireNonNullElse(turn.getText(), "")))
                    .append('\n');
        }
        message.append("\nLatest user message:\n").append(query.text()).append('\n');
        message.append("\nNumber of variants: ").append(variants).append('\n');

        Map<String, String> fields = filterValidator == null ? Map.of() : filterValidator.filterableFields(collection);
        if (fields.isEmpty()) {
            message.append("\nFilterable fields: none. Return an empty filters list.\n");
        } else {
            message.append("\nFilterable fields (name: Solr type):\n");
            fields.forEach((name, type) -> message.append("- ").append(name).append(": ").append(type).append('\n'));
        }
        return message.toString();
    }

    /**
     * Earlier user and assistant turns, most recent last, without system messages and without
     * the current question (which the history ends with); at most {@code historyMessages}.
     */
    List<Message> priorTurns(Query query) {
        List<Message> turns = allPriorTurns(query);
        return turns.size() <= historyMessages ? turns : turns.subList(turns.size() - historyMessages, turns.size());
    }

    /** {@link #priorTurns(Query)} without the {@code historyMessages} cap. */
    private static List<Message> allPriorTurns(Query query) {
        List<Message> turns = new ArrayList<>();
        for (Message message : query.history()) {
            MessageType type = message.getMessageType();
            if (type == MessageType.USER || type == MessageType.ASSISTANT) {
                turns.add(message);
            }
        }
        if (!turns.isEmpty()) {
            Message last = turns.getLast();
            if (last.getMessageType() == MessageType.USER && query.text().equals(last.getText())) {
                turns.removeLast();
            }
        }
        return turns;
    }

    /**
     * Only a missing plan or a blank standalone query rejects a plan. A wrong variant count does
     * not: the standalone rewrite and the filters are still worth using.
     */
    private static @Nullable String problemWith(@Nullable QueryPlan plan) {
        if (plan == null) {
            return "no plan";
        }
        if (isBlank(plan.standalone())) {
            return "blank standalone query";
        }
        return null;
    }

    private static void addQuery(List<Query> queries, Set<String> seen, Query original, String text,
                                 String keywordQuery, String standalone, List<String> filters) {
        if (!seen.add(normalise(text))) {
            return;
        }
        Map<String, Object> context = new HashMap<>(original.context());
        context.put(RagContextKeys.STANDALONE, standalone);
        context.put(RagContextKeys.KEYWORD_QUERY, keywordQuery);
        if (!filters.isEmpty()) {
            context.put(RagContextKeys.FILTERS, List.copyOf(filters));
        }
        queries.add(original.mutate().text(text).context(context).build());
    }

    /**
     * Writes the standalone query into the original query's context for the post-processors.
     * The advisor's map is mutable; a caller-built {@code Map.of()} is not, and is left alone.
     * That mutability is Spring AI behaviour, not API: {@code RetrievalAugmentationAdvisorContractTest}
     * pins it, so an upgrade that breaks the reranker hand-off fails there first.
     */
    private static void handOffStandalone(Query original, String standalone) {
        try {
            original.context().put(RagContextKeys.STANDALONE, standalone);
        } catch (UnsupportedOperationException e) {
            log.debug("Original query context is read-only; post-processors will see the original question");
        }
    }

    /**
     * Lower-cases, collapses whitespace runs to one space and strips trailing ASCII punctuation
     * and whitespace. The trailing strip is a backwards scan rather than {@code [\p{Punct}\s]+$},
     * which backtracks quadratically on a long punctuation run followed by a letter.
     */
    static String normalise(String text) {
        String collapsed = WHITESPACE.matcher(text.strip().toLowerCase(Locale.ROOT)).replaceAll(" ");
        int end = collapsed.length();
        while (end > 0 && TRAILING_CHAR.matcher(collapsed.subSequence(end - 1, end)).matches()) {
            end--;
        }
        return collapsed.substring(0, end);
    }

    private static List<String> nonBlank(@Nullable List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(value -> !isBlank(value)).toList();
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String text) {
        return text.length() <= MAX_HISTORY_MESSAGE_CHARS ? text : text.substring(0, MAX_HISTORY_MESSAGE_CHARS) + "…";
    }

    private static String describe(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    public static final class Builder {

        private @Nullable ChatClient plannerChatClient;
        private @Nullable String systemPrompt;
        private @Nullable FilterValidator filterValidator;
        private @Nullable String collection;
        private int variants = 2;
        private Duration timeout = Duration.ofSeconds(3);
        private int historyMessages = 10;
        private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;
        private boolean hydeEnabled;
        private @Nullable EmbeddingBatcher embeddingBatcher;
        private boolean followUpsOnly;

        private Builder() {
        }

        /**
         * Plan only questions with earlier turns in the conversation (W6,
         * {@code search.rag.planner.follow-ups-only}); false plans every question.
         */
        public Builder followUpsOnly(boolean followUpsOnly) {
            this.followUpsOnly = followUpsOnly;
            return this;
        }

        /** Use the plan's HyDE passage as the standalone query's kNN text ({@code search.rag.hyde.enabled}). */
        public Builder hydeEnabled(boolean hydeEnabled) {
            this.hydeEnabled = hydeEnabled;
            return this;
        }

        /** Embed every planned query in one request; null leaves each kNN leg to embed its own text. */
        public Builder embeddingBatcher(@Nullable EmbeddingBatcher embeddingBatcher) {
            this.embeddingBatcher = embeddingBatcher;
            return this;
        }

        /** The planner's client: a small model, no chat memory (its turns must not enter the conversation). */
        public Builder plannerChatClient(ChatClient plannerChatClient) {
            this.plannerChatClient = plannerChatClient;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /** Null disables filters: the planner is told there are no filterable fields. */
        public Builder filterValidator(@Nullable FilterValidator filterValidator) {
            this.filterValidator = filterValidator;
            return this;
        }

        public Builder collection(String collection) {
            this.collection = collection;
            return this;
        }

        public Builder variants(int variants) {
            this.variants = variants;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** How many of the most recent user/assistant messages the planner sees. */
        public Builder historyMessages(int historyMessages) {
            this.historyMessages = historyMessages;
            return this;
        }

        public Builder observationRegistry(ObservationRegistry observationRegistry) {
            this.observationRegistry = observationRegistry;
            return this;
        }

        public QueryPlanningExpander build() {
            return new QueryPlanningExpander(this);
        }
    }
}
