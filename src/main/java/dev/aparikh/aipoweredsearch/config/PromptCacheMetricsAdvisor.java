package dev.aparikh.aipoweredsearch.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Advisor that logs Anthropic prompt caching metrics when caching is enabled.
 *
 * <p>This advisor intercepts chat responses and extracts cache-related token usage
 * statistics from the Anthropic API response. It logs cache hits, misses, and cost
 * savings when prompt caching is active.</p>
 *
 * <p>Logged metrics include:</p>
 * <ul>
 *   <li>Cache creation tokens - Input tokens written to cache (cache miss)</li>
 *   <li>Cache read tokens - Input tokens read from cache (cache hit)</li>
 *   <li>Regular input tokens - Input tokens not eligible for caching</li>
 *   <li>Output tokens - Tokens generated in the response</li>
 *   <li>Cost savings - Percentage reduction in costs from cache hits</li>
 * </ul>
 *
 * <p>Example log output:</p>
 * <pre>
 * [Prompt Caching] Cache HIT - Read: 2048 tokens, Regular: 256 tokens, Output: 150 tokens
 * [Prompt Caching] Cost savings: ~80% (cache read is 90% cheaper than regular input)
 * </pre>
 *
 * @author Aditya Parikh
 * @see AnthropicApi.Usage
 */
public class PromptCacheMetricsAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(PromptCacheMetricsAdvisor.class);

    private final boolean cachingEnabled;

    public PromptCacheMetricsAdvisor(boolean cachingEnabled) {
        this.cachingEnabled = cachingEnabled;
        log.info("[Prompt Caching] PromptCacheMetricsAdvisor@{} initialized with cachingEnabled={}, order={}",
                System.identityHashCode(this), cachingEnabled, getOrder());
    }

    /**
     * Returns a new builder for {@link PromptCacheMetricsAdvisor}.
     *
     * <p>This enables a consistent builder paradigm alongside other Spring AI advisors
     * that already expose builders.</p>
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link PromptCacheMetricsAdvisor}.
     */
    public static final class Builder {
        private boolean cachingEnabled;

        private Builder() {
        }

        /**
         * Enables or disables prompt caching metrics logging.
         *
         * @param cachingEnabled whether caching is enabled
         * @return this builder instance
         */
        public Builder cachingEnabled(boolean cachingEnabled) {
            this.cachingEnabled = cachingEnabled;
            return this;
        }

        /**
         * Builds the {@link PromptCacheMetricsAdvisor}.
         *
         * @return a configured advisor instance
         */
        public PromptCacheMetricsAdvisor build() {
            return new PromptCacheMetricsAdvisor(this.cachingEnabled);
        }
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        log.info("[Prompt Caching] PromptCacheMetricsAdvisor@{}.before() invoked",
                System.identityHashCode(this));
        // Just pass through - we only care about the response
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        try {
            log.info("[Prompt Caching] PromptCacheMetricsAdvisor@{}.after() invoked, cachingEnabled={}, response={}, chatResponse={}",
                    System.identityHashCode(this), cachingEnabled, response != null, response != null && response.chatResponse() != null);

            // Log cache metrics if caching is enabled
            if (cachingEnabled && response != null && response.chatResponse() != null) {
                logCacheMetrics(response.chatResponse());
            }

            log.info("[Prompt Caching] PromptCacheMetricsAdvisor@{}.after() END", System.identityHashCode(this));
            return response;
        } catch (Exception e) {
            log.error("[Prompt Caching] PromptCacheMetricsAdvisor@{}.after() ERROR", System.identityHashCode(this), e);
            throw e;
        }
    }

    /**
     * Extracts and logs cache-related metrics from the chat response.
     *
     * @param response the chat response from Anthropic API
     */
    private void logCacheMetrics(ChatResponse response) {
        Usage usage = response.getMetadata().getUsage();
        if (usage == null || usage.getNativeUsage() == null) {
            log.debug("[Prompt Caching] No usage metadata available");
            return;
        }

        Object nativeUsage = usage.getNativeUsage();
        if (!(nativeUsage instanceof AnthropicApi.Usage anthropicUsage)) {
            log.debug("[Prompt Caching] Native usage is not AnthropicApi.Usage: {}",
                    nativeUsage != null ? nativeUsage.getClass().getName() : "null");
            return;
        }

        Integer cacheCreationTokens = anthropicUsage.cacheCreationInputTokens();
        Integer cacheReadTokens = anthropicUsage.cacheReadInputTokens();
        Integer regularInputTokens = anthropicUsage.inputTokens();
        Integer outputTokens = anthropicUsage.outputTokens();

        log.debug("[Prompt Caching] Token metrics - Creation: {}, Read: {}, Regular: {}, Output: {}",
                cacheCreationTokens, cacheReadTokens, regularInputTokens, outputTokens);

        // Only log if there's cache activity
        if ((cacheCreationTokens != null && cacheCreationTokens > 0) ||
                (cacheReadTokens != null && cacheReadTokens > 0)) {

            logCacheActivity(cacheCreationTokens, cacheReadTokens, regularInputTokens, outputTokens);
        } else {
            log.debug("[Prompt Caching] No cache activity detected. " +
                    "Prompt may be too small (<1024 tokens) or caching not configured properly.");
        }
    }

    /**
     * Logs cache activity with detailed metrics and cost savings calculation.
     *
     * @param cacheCreationTokens tokens written to cache (cache miss)
     * @param cacheReadTokens     tokens read from cache (cache hit)
     * @param regularInputTokens  regular input tokens not cached
     * @param outputTokens        output tokens generated
     */
    private void logCacheActivity(Integer cacheCreationTokens, Integer cacheReadTokens,
                                  Integer regularInputTokens, Integer outputTokens) {

        boolean isCacheHit = cacheReadTokens != null && cacheReadTokens > 0;
        boolean isCacheMiss = cacheCreationTokens != null && cacheCreationTokens > 0;

        if (isCacheHit) {
            log.info("[Prompt Caching] Cache HIT - Read: {} tokens, Regular input: {} tokens, Output: {} tokens",
                    cacheReadTokens, regularInputTokens != null ? regularInputTokens : 0, outputTokens != null ? outputTokens : 0);

            // Calculate approximate cost savings
            // According to Anthropic: cache reads are 90% cheaper than regular input tokens
            // Reference: https://www.anthropic.com/news/prompt-caching
            double savingsPercentage = calculateSavings(cacheReadTokens, regularInputTokens);
            log.info("[Prompt Caching] Cost savings: ~{}%",
                    String.format("%.1f", savingsPercentage));

        } else if (isCacheMiss) {
            log.info("[Prompt Caching] Cache MISS - Created: {} tokens, Regular input: {} tokens, Output: {} tokens",
                    cacheCreationTokens, regularInputTokens != null ? regularInputTokens : 0, outputTokens != null ? outputTokens : 0);
            log.info("[Prompt Caching] Cache created. Subsequent requests with identical prompts will benefit from ~90% cost reduction");
        }
    }

    /**
     * Calculates the approximate cost savings from cache hits.
     *
     * <h3>Background: Anthropic Prompt Caching</h3>
     * <p>When a cached prompt is reused, Anthropic charges 90% less for those input tokens
     * compared to a regular (uncached) prompt. So, cached reads cost only 10% of regular input tokens.</p>
     *
     * <h3>Cost Calculation Logic</h3>
     * <p>Let's define:</p>
     * <ul>
     *   <li><b>C</b> = cacheReadTokens (number of tokens read from cache)</li>
     *   <li><b>R</b> = regularInputTokens (regular tokens, not eligible for caching)</li>
     *   <li><b>Total Input Tokens</b>: T = C + R</li>
     *   <li><b>Regular cost per token</b>: Assume it's 1 unit of cost</li>
     * </ul>
     *
     * <p><b>Step 1: Cost Without Caching</b></p>
     * <p>If there were no caching, you'd pay full price for all input tokens:</p>
     * <pre>costWithoutCaching = (C + R) × 1.0</pre>
     *
     * <p><b>Step 2: Cost With Caching</b></p>
     * <p>Now, with Anthropic caching:</p>
     * <ul>
     *   <li>Cached tokens cost: C × 0.1 (since cache reads are 90% cheaper)</li>
     *   <li>Regular tokens cost: R × 1.0</li>
     * </ul>
     * <pre>totalEffectiveCost = (C × 0.1) + (R × 1.0)</pre>
     *
     * <p><b>Step 3: Cost Savings Calculation</b></p>
     * <p>The percentage saved compared to no caching:</p>
     * <pre>savings = ((costWithoutCaching - totalEffectiveCost) / costWithoutCaching) × 100</pre>
     *
     * <p><b>Example:</b></p>
     * <p>Suppose C = 1000 (cached) and R = 100 (regular):</p>
     * <ul>
     *   <li>Without caching: 1100 × 1.0 = 1100</li>
     *   <li>With caching: (1000 × 0.1) + (100 × 1.0) = 100 + 100 = 200</li>
     *   <li>Savings: (1100 - 200) / 1100 × 100 = 900 / 1100 × 100 ≈ 81.8%</li>
     * </ul>
     *
     * @param cacheReadTokens    number of tokens read from cache
     * @param regularInputTokens number of regular input tokens
     * @return percentage of cost savings due to prompt caching
     */
    private double calculateSavings(Integer cacheReadTokens, Integer regularInputTokens) {
        if (cacheReadTokens == null || cacheReadTokens == 0) {
            return 0.0;
        }

        int regularTokens = regularInputTokens != null ? regularInputTokens : 0;
        int totalInputTokens = cacheReadTokens + regularTokens;

        if (totalInputTokens == 0) {
            return 0.0;
        }

        // Cache reads cost 10% of regular reads (90% savings)
        double effectiveCachedCost = cacheReadTokens * 0.1;
        double regularCost = regularTokens * 1.0;
        double totalEffectiveCost = effectiveCachedCost + regularCost;

        // What would it have cost without caching?
        double costWithoutCaching = totalInputTokens * 1.0;

        return ((costWithoutCaching - totalEffectiveCost) / costWithoutCaching) * 100;
    }

    @Override
    public String getName() {
        return "PromptCacheMetricsAdvisor";
    }

    @Override
    public int getOrder() {
        // Use same order as SimpleLoggerAdvisor (0) to ensure we're invoked
        return 0;
    }
}
