package dev.aparikh.aipoweredsearch.config;

import dev.aparikh.aipoweredsearch.search.RerankingDocumentPostProcessor;
import dev.aparikh.aipoweredsearch.search.rag.RagPostProcessors;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.typesafe.JevBatchOptions;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.rag.JevDocumentFilter;
import org.springaicommunity.typesafe.rag.JevDocumentReranker;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Locale;

/**
 * Assembles the RAG pipeline's document post-processors (W4, #38): an optional TypeSafe Jev
 * passage filter, then the reranker.
 *
 * <h2>Jev is optional and never required at startup</h2>
 * <p>The TypeSafe starter's auto-configuration is excluded in {@code application.properties}. It
 * activates whenever {@code spring.ai.typesafe.api-key} is <em>present</em>, and an empty value,
 * which is what {@code ${TYPESAFE_API_KEY:}} resolves to when the variable is unset, fails
 * startup (W0 finding A2). This class builds the {@link TypeSafeClient} itself, and only when a
 * Jev stage is enabled. If no key is configured, it logs a WARN and runs without Jev.</p>
 *
 * <h2>Properties</h2>
 * <ul>
 *   <li>{@code search.rag.jev.enabled} (default {@code false}): screen candidates with
 *       {@code JevDocumentFilter} before reranking</li>
 *   <li>{@code search.rag.jev.concurrency} (default {@code 4}): parallel Jev calls</li>
 *   <li>{@code search.rag.jev.timeout} (default {@code 5s}): upper bound on the filter; on expiry
 *       the candidates pass through</li>
 *   <li>{@code search.rag.rerank.provider} (default {@code claude}): {@code claude} or {@code jev}</li>
 *   <li>{@code search.rag.rerank.model} (default {@code claude-sonnet-4-5}): the Claude reranker's
 *       model, see {@code AiConfig#rerankingDocumentPostProcessor}</li>
 *   <li>{@code search.rag.rerank.short-circuit} (default: the value of {@code search.rag.jev.enabled}):
 *       skip the reranker when the candidates already fit in {@code search.rag.rerank.top-k}</li>
 *   <li>{@code spring.ai.typesafe.api-key} ({@code ${TYPESAFE_API_KEY:}}), {@code spring.ai.typesafe.base-url},
 *       {@code spring.ai.typesafe.timeout}</li>
 * </ul>
 */
@Configuration
public class RagPostProcessingConfig {

    private static final Logger log = LoggerFactory.getLogger(RagPostProcessingConfig.class);

    static final String CLAUDE = "claude";
    static final String JEV = "jev";

    @Bean
    public RagPostProcessors ragPostProcessors(
            ObjectProvider<RerankingDocumentPostProcessor> claudeReranker,
            ObjectProvider<ObservationRegistry> observationRegistry,
            @Value("${search.rag.rerank.enabled:true}") boolean rerankEnabled,
            @Value("${search.rag.rerank.provider:claude}") String provider,
            @Value("${search.rag.rerank.top-k:5}") int rerankTopK,
            @Value("${search.rag.jev.enabled:false}") boolean jevEnabled,
            @Value("${search.rag.rerank.short-circuit:${search.rag.jev.enabled:false}}") boolean shortCircuit,
            @Value("${search.rag.jev.concurrency:4}") int jevConcurrency,
            @Value("${search.rag.jev.timeout:5s}") Duration jevTimeout,
            @Value("${spring.ai.typesafe.api-key:}") String typeSafeApiKey,
            @Value("${spring.ai.typesafe.base-url:}") String typeSafeBaseUrl,
            @Value("${spring.ai.typesafe.timeout:10s}") Duration typeSafeTimeout) {

        String rerankProvider = provider.strip().toLowerCase(Locale.ROOT);
        if (!CLAUDE.equals(rerankProvider) && !JEV.equals(rerankProvider)) {
            throw new IllegalArgumentException(
                    "search.rag.rerank.provider must be 'claude' or 'jev', got: '" + provider + "'");
        }
        boolean jevNeeded = jevEnabled || (rerankEnabled && JEV.equals(rerankProvider));
        @Nullable TypeSafeClient typeSafe = jevNeeded ? typeSafeClient(typeSafeApiKey, typeSafeBaseUrl, typeSafeTimeout) : null;
        JevBatchOptions batchOptions = JevBatchOptions.ofConcurrency(jevConcurrency);

        @Nullable DocumentPostProcessor jevFilter = null;
        if (jevEnabled && typeSafe != null) {
            jevFilter = JevDocumentFilter.builder(typeSafe).batchOptions(batchOptions).build();
        }

        @Nullable DocumentPostProcessor reranker = null;
        if (rerankEnabled) {
            if (JEV.equals(rerankProvider) && typeSafe != null) {
                reranker = JevDocumentReranker.builder(typeSafe).topK(rerankTopK).batchOptions(batchOptions).build();
            } else {
                if (JEV.equals(rerankProvider)) {
                    log.warn("search.rag.rerank.provider=jev but TypeSafe is not configured; reranking with Claude");
                }
                reranker = claudeReranker.getIfAvailable();
            }
        }

        RagPostProcessors chain = RagPostProcessors.assemble(jevFilter, jevTimeout, reranker, rerankTopK,
                shortCircuit, observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP));
        log.info("RAG post-processors: jev filter={}, reranker={}, short-circuit={}",
                jevFilter != null, reranker == null ? "none" : reranker.getClass().getSimpleName(), shortCircuit);
        return chain;
    }

    /**
     * Builds the TypeSafe client, or returns null (with a WARN) when no API key is configured.
     */
    static @Nullable TypeSafeClient typeSafeClient(String apiKey, String baseUrl, Duration timeout) {
        if (apiKey.isBlank()) {
            log.warn("A Jev stage is enabled but spring.ai.typesafe.api-key (TYPESAFE_API_KEY) is not set; "
                    + "continuing without Jev");
            return null;
        }
        TypeSafeClient.Builder builder = TypeSafeClient.builder().apiKey(apiKey).timeout(timeout);
        if (!baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }
}
