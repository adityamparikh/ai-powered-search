package dev.aparikh.aipoweredsearch.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
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
public class PromptCacheMetricsAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(PromptCacheMetricsAdvisor.class);

    private final boolean cachingEnabled;

    public PromptCacheMetricsAdvisor(boolean cachingEnabled) {
        this.cachingEnabled = cachingEnabled;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        // Continue the chain
        ChatClientResponse response = chain.nextCall(request);

        // Log cache metrics if caching is enabled
        if (cachingEnabled && response != null && response.chatResponse() != null) {
            logCacheMetrics(response.chatResponse());
        }

        return response;
    }

    /**
     * Extracts and logs cache-related metrics from the chat response.
     *
     * @param response the chat response from Anthropic API
     */
    private void logCacheMetrics(ChatResponse response) {
        Usage usage = response.getMetadata().getUsage();
        if (usage == null || usage.getNativeUsage() == null) {
            return;
        }

        Object nativeUsage = usage.getNativeUsage();
        if (!(nativeUsage instanceof AnthropicApi.Usage anthropicUsage)) {
            return;
        }

        Integer cacheCreationTokens = anthropicUsage.cacheCreationInputTokens();
        Integer cacheReadTokens = anthropicUsage.cacheReadInputTokens();
        Integer regularInputTokens = anthropicUsage.inputTokens();
        Integer outputTokens = anthropicUsage.outputTokens();

        // Only log if there's cache activity
        if ((cacheCreationTokens != null && cacheCreationTokens > 0) ||
                (cacheReadTokens != null && cacheReadTokens > 0)) {

            logCacheActivity(cacheCreationTokens, cacheReadTokens, regularInputTokens, outputTokens);
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
            log.info("[Prompt Caching] Cost savings: ~{,number,#.#}% (cache reads are 90% cheaper than regular input)",
                    savingsPercentage);

        } else if (isCacheMiss) {
            log.info("[Prompt Caching] Cache MISS - Created: {} tokens, Regular input: {} tokens, Output: {} tokens",
                    cacheCreationTokens, regularInputTokens != null ? regularInputTokens : 0, outputTokens != null ? outputTokens : 0);
            log.info("[Prompt Caching] Cache created. Subsequent requests with identical prompts will benefit from ~90% cost reduction");
        }
    }

    /**
     * Calculates the approximate cost savings from cache hits.
     *
     * <p>Formula: savings = (cached_tokens * 0.9) / (cached_tokens + regular_tokens) * 100</p>
     * <p>This assumes cache reads are 90% cheaper than regular input tokens.</p>
     *
     * @param cacheReadTokens    number of tokens read from cache
     * @param regularInputTokens number of regular input tokens
     * @return percentage of cost savings
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
        // Run last to ensure we capture the final response
        return Integer.MAX_VALUE;
    }
}
