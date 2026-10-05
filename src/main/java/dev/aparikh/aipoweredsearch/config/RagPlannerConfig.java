package dev.aparikh.aipoweredsearch.config;

import dev.aparikh.aipoweredsearch.search.SearchRepository;
import dev.aparikh.aipoweredsearch.search.rag.EmbeddingBatcher;
import dev.aparikh.aipoweredsearch.search.rag.FilterValidator;
import dev.aparikh.aipoweredsearch.search.rag.QueryPlanningExpander;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Beans for the RAG query planner (W1, #36). Nothing here exists unless
 * {@code search.rag.planner.enabled=true}; with it off, {@code /ask} behaves exactly as before.
 *
 * <h2>Properties</h2>
 * <ul>
 *   <li>{@code search.rag.planner.enabled} (default {@code false})</li>
 *   <li>{@code search.rag.planner.model} (default {@code claude-haiku-4-5}, W0 finding A3)</li>
 *   <li>{@code search.rag.planner.variants} (default {@code 2})</li>
 *   <li>{@code search.rag.planner.timeout} (default {@code 3s})</li>
 *   <li>{@code search.rag.planner.history-messages} (default {@code 10})</li>
 *   <li>{@code search.rag.planner.filters.enabled} (default {@code false})</li>
 *   <li>{@code search.rag.planner.filters.field-cache-ttl} (default {@code 5m})</li>
 *   <li>{@code search.rag.hyde.enabled} (default {@code false}, W2)</li>
 *   <li>{@code search.rag.planner.follow-ups-only} (default {@code false}, W6)</li>
 * </ul>
 */
@Configuration
public class RagPlannerConfig {

    private static final Logger log = LoggerFactory.getLogger(RagPlannerConfig.class);

    /**
     * Warns when HyDE is enabled without the planner: the planner writes the HyDE passage, so the
     * flag would otherwise do nothing, silently.
     */
    public RagPlannerConfig(@Value("${search.rag.planner.enabled:false}") boolean plannerEnabled,
                            @Value("${search.rag.hyde.enabled:false}") boolean hydeEnabled) {
        if (hydeEnabled && !plannerEnabled) {
            log.warn("search.rag.hyde.enabled=true has no effect without search.rag.planner.enabled=true");
        }
    }

    /**
     * The planner's own {@link ChatClient} on a small model.
     *
     * <p>It deliberately has <strong>no chat-memory advisor</strong> and does not inherit
     * {@code ragChatClient}'s advisors: the planner's prompt and reply must never enter the
     * user's conversation. The history it needs arrives explicitly in its prompt.</p>
     *
     * <p>No prompt-caching options are set. Claude Haiku 4.5's minimum cacheable prompt is 4096
     * tokens and the planner's system prompt is roughly 1K, so a cache breakpoint would silently do
     * nothing (W0 finding A3). Revisit if the prompt grows past the minimum.</p>
     *
     * <p>The planner timeout is also set as this client's per-call HTTP timeout. The expander
     * already stops waiting and cancels its thread at the timeout; this makes the SDK abort the
     * request itself, so an abandoned call does not run on (and bill) for the 60s default.</p>
     */
    @Bean
    @ConditionalOnProperty(name = "search.rag.planner.enabled", havingValue = "true")
    public ChatClient plannerChatClient(ChatModel chatModel,
                                        @Value("${search.rag.planner.model:claude-haiku-4-5}") String model,
                                        @Value("${search.rag.planner.timeout:3s}") Duration timeout) {
        return ChatClient.builder(chatModel)
                .defaultOptions(AnthropicChatOptions.builder()
                        .model(model)
                        .maxTokens(1500)
                        .timeout(timeout))
                .defaultAdvisors(SimpleLoggerAdvisor.builder().build())
                .build();
    }

    /**
     * Validates the planner's filters against the collection's schema. Present only when
     * planner filters are enabled.
     */
    @Bean
    @ConditionalOnProperty(name = {"search.rag.planner.enabled", "search.rag.planner.filters.enabled"}, havingValue = "true")
    public FilterValidator filterValidator(SearchRepository searchRepository,
                                           @Value("${search.rag.planner.filters.field-cache-ttl:5m}") Duration ttl) {
        return new FilterValidator(searchRepository, ttl);
    }

    /**
     * Embeds every planned query of a turn in one request (W2). Present with the planner.
     */
    @Bean
    @ConditionalOnProperty(name = "search.rag.planner.enabled", havingValue = "true")
    public EmbeddingBatcher embeddingBatcher(EmbeddingModel embeddingModel) {
        return new EmbeddingBatcher(embeddingModel);
    }

    /**
     * The query planner. With {@code search.rag.planner.follow-ups-only=true} it plans only
     * questions that have earlier turns (W6). Planner filters and HyDE come from the plan, so a first
     * question then gets neither, and a WARN at startup says so when either is enabled.
     */
    @Bean
    @ConditionalOnProperty(name = "search.rag.planner.enabled", havingValue = "true")
    public QueryPlanningExpander queryPlanningExpander(
            @Qualifier("plannerChatClient") ChatClient plannerChatClient,
            @Value("classpath:/prompts/query-planner.st") Resource systemPrompt,
            ObjectProvider<FilterValidator> filterValidator,
            ObjectProvider<ObservationRegistry> observationRegistry,
            @Value("${solr.default.collection:books}") String collection,
            @Value("${search.rag.planner.variants:2}") int variants,
            @Value("${search.rag.planner.timeout:3s}") Duration timeout,
            @Value("${search.rag.planner.history-messages:10}") int historyMessages,
            @Value("${search.rag.hyde.enabled:false}") boolean hydeEnabled,
            @Value("${search.rag.planner.follow-ups-only:false}") boolean followUpsOnly,
            EmbeddingBatcher embeddingBatcher) throws IOException {
        @Nullable FilterValidator validator = filterValidator.getIfAvailable();
        if (followUpsOnly && (validator != null || hydeEnabled)) {
            log.warn("search.rag.planner.follow-ups-only is on: first questions in a conversation are not "
                    + "planned, so they get no planner filters or HyDE passage. Turn it off if first "
                    + "questions need them.");
        }
        return QueryPlanningExpander.builder()
                .plannerChatClient(plannerChatClient)
                .systemPrompt(systemPrompt.getContentAsString(StandardCharsets.UTF_8))
                .filterValidator(validator)
                .collection(collection)
                .variants(variants)
                .timeout(timeout)
                .historyMessages(historyMessages)
                .observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
                .hydeEnabled(hydeEnabled)
                .embeddingBatcher(embeddingBatcher)
                .followUpsOnly(followUpsOnly)
                .build();
    }
}
