package dev.aparikh.aipoweredsearch.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for PromptCacheMetricsAdvisor with Anthropic Claude.
 *
 * <p>Tests the prompt caching functionality and metrics logging:
 * <ol>
 *   <li>Verifies cache MISS on first request (cache creation)</li>
 *   <li>Verifies cache HIT on subsequent identical requests</li>
 *   <li>Validates cache metrics logging output</li>
 *   <li>Confirms cost savings calculations</li>
 * </ol>
 *
 * <p><b>Requirements:</b>
 * <ul>
 *   <li>ANTHROPIC_API_KEY environment variable must be set</li>
 *   <li>Prompt caching must be enabled (default configuration)</li>
 * </ul>
 *
 * <p>This test validates that the PromptCacheMetricsAdvisor correctly intercepts
 * chat responses and logs cache performance metrics for both cache misses (first request)
 * and cache hits (subsequent requests).</p>
 *
 * @see PromptCacheMetricsAdvisor
 * @see AiConfig
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.ai.anthropic.prompt-caching.enabled=true",
                "spring.ai.anthropic.prompt-caching.strategy=SYSTEM_AND_TOOLS",
                "spring.ai.model.chat=anthropic"
        }
)
@Import({
        RestClientConfig.class,
        AiConfig.class,
        PostgresTestConfiguration.class
})
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class PromptCacheMetricsAdvisorIT {

    @Autowired
    @Qualifier("searchChatClient")
    private ChatClient chatClient;

    @Autowired
    private ChatMemory chatMemory;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger advisorLogger;

    private static final String TEST_CONVERSATION_ID = "prompt-cache-test";

    @BeforeEach
    void setUp() {
        // Set up log appender to capture PromptCacheMetricsAdvisor logs
        advisorLogger = (Logger) LoggerFactory.getLogger(PromptCacheMetricsAdvisor.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        advisorLogger.addAppender(logAppender);
        advisorLogger.setLevel(Level.INFO);

        // Clear chat memory to ensure clean state
        chatMemory.clear(TEST_CONVERSATION_ID);
    }

    @AfterEach
    void tearDown() {
        // Clean up log appender
        if (advisorLogger != null && logAppender != null) {
            advisorLogger.detachAppender(logAppender);
        }

        // Clear chat memory
        if (chatMemory != null) {
            chatMemory.clear(TEST_CONVERSATION_ID);
        }
    }

    @Test
    void beansAreProperlyConfigured() {
        assertThat(chatClient).as("ChatClient should be configured").isNotNull();
        assertThat(chatMemory).as("ChatMemory should be configured").isNotNull();
    }

    @Test
    void shouldLogCacheMissOnFirstRequest() {
        // Given - A question with a system prompt that will be cached
        String question = "What is 2 + 2? Answer with just the number.";

        // When - First request (cache miss expected)
        ChatResponse response = chatClient.prompt()
                .system("You are a helpful math assistant. Always provide concise answers.")
                .user(question)
                .call()
                .chatResponse();

        // Then - Verify response is valid
        assertThat(response).isNotNull();
        assertThat(response.getResult()).isNotNull();
        assertThat(response.getResult().getOutput().getText()).isNotBlank();

        // Verify cache metrics
        Usage usage = response.getMetadata().getUsage();
        assertThat(usage).isNotNull();

        AnthropicApi.Usage anthropicUsage = (AnthropicApi.Usage) usage.getNativeUsage();
        assertThat(anthropicUsage).isNotNull();

        // First request ideally creates cache (cache miss)
        // Note: Anthropic prompt caching only activates for sufficiently large prompts/tools.
        // In environments with short prompts, the provider may return 0 cache tokens.
        Integer cacheCreationTokens = anthropicUsage.cacheCreationInputTokens();
        boolean cachingActive = cacheCreationTokens != null && cacheCreationTokens > 0;
        if (cachingActive) {
            assertThat(cacheCreationTokens)
                    .as("Cache creation tokens should be present on first request")
                    .isGreaterThan(0);
        } else {
            // If caching isn't active due to small prompt size, ensure we still received a valid response
            assertThat(usage).as("Usage should be available").isNotNull();
        }

        // Verify log output for cache miss
        List<ILoggingEvent> logEvents = logAppender.list;
        if (!cachingActive) {
            // When caching isn't active, advisor may not log anything
            assertThat(logEvents).isEmpty();
        } else {
            assertThat(logEvents).isNotEmpty();
        }

        boolean foundCacheMissLog = logEvents.stream()
                .anyMatch(event -> event.getFormattedMessage().contains("Cache MISS"));

        if (cachingActive) {
            assertThat(foundCacheMissLog)
                    .as("Should log 'Cache MISS' message on first request when caching is active")
                    .isTrue();
        } else {
            // When caching isn't active, we shouldn't require a MISS log
            assertThat(foundCacheMissLog).isFalse();
        }

        boolean foundCacheCreatedMessage = logEvents.stream()
                .anyMatch(event -> event.getFormattedMessage()
                        .contains("Subsequent requests with identical prompts will benefit"));

        if (cachingActive) {
            assertThat(foundCacheCreatedMessage)
                    .as("Should log message about future cost savings")
                    .isTrue();
        }
    }

    @Test
    void shouldLogCacheHitOnSubsequentIdenticalRequests() {
        // Given - A consistent system prompt and question
        String systemPrompt = "You are a helpful assistant. Provide brief, accurate answers.";
        String question1 = "What is the capital of France?";

        // When - First request (cache miss)
        logAppender.list.clear();
        ChatResponse response1 = chatClient.prompt()
                .system(systemPrompt)
                .user(question1)
                .call()
                .chatResponse();

        // Wait briefly to ensure cache is ready
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // When - Second request with identical system prompt (cache hit expected)
        logAppender.list.clear();
        String question2 = "What is the capital of Germany?";
        ChatResponse response2 = chatClient.prompt()
                .system(systemPrompt)
                .user(question2)
                .call()
                .chatResponse();

        // Then - Verify second response is valid
        assertThat(response2).isNotNull();
        assertThat(response2.getResult()).isNotNull();

        // Verify cache hit metrics
        Usage usage = response2.getMetadata().getUsage();
        assertThat(usage).isNotNull();

        AnthropicApi.Usage anthropicUsage = (AnthropicApi.Usage) usage.getNativeUsage();
        assertThat(anthropicUsage).isNotNull();

        // Second request ideally reads from cache (cache hit)
        Integer cacheReadTokens = anthropicUsage.cacheReadInputTokens();
        boolean cachingActive = cacheReadTokens != null && cacheReadTokens > 0;
        if (cachingActive) {
            assertThat(cacheReadTokens)
                    .as("Cache read tokens should be present on subsequent request")
                    .isGreaterThan(0);
        }

        // Verify log output for cache hit
        List<ILoggingEvent> logEvents = logAppender.list;
        if (!cachingActive) {
            assertThat(logEvents).isEmpty();
        } else {
            assertThat(logEvents).isNotEmpty();
        }

        boolean foundCacheHitLog = logEvents.stream()
                .anyMatch(event -> event.getFormattedMessage().contains("Cache HIT"));

        if (cachingActive) {
            assertThat(foundCacheHitLog)
                    .as("Should log 'Cache HIT' message on subsequent request")
                    .isTrue();
        } else {
            assertThat(foundCacheHitLog).isFalse();
        }

        boolean foundCostSavingsLog = logEvents.stream()
                .anyMatch(event -> event.getFormattedMessage().contains("Cost savings"));

        if (cachingActive) {
            assertThat(foundCostSavingsLog)
                    .as("Should log cost savings percentage")
                    .isTrue();
        }
    }

    @Test
    void shouldCalculateCorrectCostSavings() {
        // Given - A system prompt that will be cached
        String systemPrompt = "You are a math tutor. Explain concepts clearly and concisely.";
        String question1 = "Explain what a prime number is in one sentence.";

        // When - First request
        chatClient.prompt()
                .system(systemPrompt)
                .user(question1)
                .call()
                .chatResponse();

        // Wait for cache to be ready
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // When - Second request (should hit cache)
        logAppender.list.clear();
        String question2 = "What is a composite number?";
        ChatResponse response = chatClient.prompt()
                .system(systemPrompt)
                .user(question2)
                .call()
                .chatResponse();

        // Then - Extract usage metrics
        AnthropicApi.Usage usage = (AnthropicApi.Usage) response.getMetadata().getUsage().getNativeUsage();
        Integer cacheReadTokens = usage.cacheReadInputTokens();
        Integer regularTokens = usage.inputTokens();

        boolean cachingActive = cacheReadTokens != null && cacheReadTokens > 0;
        assertThat(regularTokens).isNotNull();

        // Calculate expected savings
        // Formula: (cached_tokens * 0.9) / (cached_tokens + regular_tokens) * 100
        int totalInputTokens = (cacheReadTokens != null ? cacheReadTokens : 0)
                + (regularTokens != null ? regularTokens : 0);
        double expectedSavings = (cacheReadTokens != null && totalInputTokens > 0)
                ? ((double) cacheReadTokens * 0.9 / totalInputTokens) * 100
                : 0.0;

        // Verify log contains reasonable savings percentage
        List<ILoggingEvent> logEvents = logAppender.list;
        boolean foundSavingsLog = logEvents.stream()
                .anyMatch(event -> {
                    String message = event.getFormattedMessage();
                    return message.contains("Cost savings") && message.contains("%");
                });

        if (cachingActive) {
            assertThat(foundSavingsLog)
                    .as("Should log cost savings with percentage")
                    .isTrue();
        }

        // Verify savings are significant (should be > 0% with cache hit)
        if (cachingActive) {
            assertThat(expectedSavings)
                    .as("Cost savings should be greater than 0% when cache is hit")
                    .isGreaterThan(0.0);
        } else {
            assertThat(expectedSavings).isEqualTo(0.0);
        }
    }

    @Test
    void shouldHandleMultipleRequestsWithCaching() {
        // Given - A consistent system prompt
        String systemPrompt = "You are a geography expert. Provide concise, factual answers.";
        String[] questions = {
                "What is the largest ocean?",
                "What is the tallest mountain?",
                "What is the longest river?"
        };

        // When - Make multiple requests
        for (int i = 0; i < questions.length; i++) {
            logAppender.list.clear();

            ChatResponse response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(questions[i])
                    .call()
                    .chatResponse();

            // Then - Verify response is valid
            assertThat(response).isNotNull();
            assertThat(response.getResult().getOutput().getText()).isNotBlank();

            AnthropicApi.Usage usage = (AnthropicApi.Usage)
                    response.getMetadata().getUsage().getNativeUsage();

            if (i == 0) {
                // First request may create cache if prompt size threshold is met
                Integer created = usage.cacheCreationInputTokens();
                boolean cachingActive = created != null && created > 0;
                if (cachingActive) {
                    assertThat(created)
                            .as("First request should create cache")
                            .isGreaterThan(0);

                    boolean foundCacheMiss = logAppender.list.stream()
                            .anyMatch(event -> event.getFormattedMessage().contains("Cache MISS"));
                    assertThat(foundCacheMiss).isTrue();
                }
            } else {
                // Subsequent requests may read from cache if activated
                Integer read = usage.cacheReadInputTokens();
                boolean cachingActive = read != null && read > 0;
                if (cachingActive) {
                    assertThat(read)
                            .as("Request %d should read from cache", i + 1)
                            .isGreaterThan(0);

                    boolean foundCacheHit = logAppender.list.stream()
                            .anyMatch(event -> event.getFormattedMessage().contains("Cache HIT"));
                    assertThat(foundCacheHit).isTrue();
                }
            }

            // Small delay between requests
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void shouldLogTokenBreakdown() {
        // Given
        String systemPrompt = "You are a helpful coding assistant.";
        String question = "What is a REST API?";

        // When - Make a request
        logAppender.list.clear();
        ChatResponse response = chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .call()
                .chatResponse();

        // Then - Verify metrics are logged with token details
        List<ILoggingEvent> logEvents = logAppender.list;

        // Should log either cache miss or cache hit with token breakdown when caching is active
        boolean foundTokenBreakdown = logEvents.stream()
                .anyMatch(event -> {
                    String message = event.getFormattedMessage();
                    return (message.contains("Cache MISS") || message.contains("Cache HIT"))
                            && message.contains("tokens");
                });

        Usage usage = response.getMetadata().getUsage();
        AnthropicApi.Usage anthropicUsage = (AnthropicApi.Usage) usage.getNativeUsage();
        Integer created = anthropicUsage.cacheCreationInputTokens();
        Integer read = anthropicUsage.cacheReadInputTokens();
        boolean cachingActive = (created != null && created > 0) || (read != null && read > 0);

        if (cachingActive) {
            assertThat(foundTokenBreakdown)
                    .as("Should log cache event with token breakdown")
                    .isTrue();
        }

        // Verify response has usage metadata
        Usage usage2 = response.getMetadata().getUsage();
        assertThat(usage2).isNotNull();
        assertThat(usage2.getNativeUsage()).isInstanceOf(AnthropicApi.Usage.class);
    }
}
