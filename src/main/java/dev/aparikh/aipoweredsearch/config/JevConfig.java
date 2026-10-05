package dev.aparikh.aipoweredsearch.config;

import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.rag.JevDocumentFilter;
import org.springaicommunity.typesafe.rag.JevDocumentReranker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Optional TypeSafe Jev passage screening and reranking for RAG.
 *
 * <p>{@link JevDocumentFilter} screens each retrieved passage for prompt injection, contradiction of
 * the question's premise, relevance and answer evidence, and drops the ones that fail, before the
 * reranker runs. It is off by default: it sends the passages and the question to TypeSafe's hosted
 * API, one call per passage, which adds latency. A passage Jev cannot screen (the API errors or is
 * unreachable) is passed through with a WARN, so screening is best-effort.</p>
 *
 * <p>{@link JevDocumentReranker}, selected with {@code search.rag.rerank.provider=jev}, replaces the
 * Claude reranker. It asks one question of each passage, "could this passage answer the query?", and
 * keeps the {@code search.rag.rerank.top-k} best. Unlike the Claude reranker it only discards
 * passages scoring below {@code search.rag.jev.rerank.minimum-score} (default 0, keep all). A passage
 * it cannot score is kept after the scored ones.</p>
 *
 * <p>Each bean exists only with its switch and a {@code spring.ai.typesafe.api-key}. The key condition mirrors the TypeSafe starter's own, which
 * auto-configures the {@link TypeSafeClient} only when the key is set. ({@code @ConditionalOnBean}
 * would be evaluated before the starter registers the client, outside an auto-configuration.)</p>
 */
@Configuration(proxyBeanMethods = false)
public class JevConfig {

    @Bean
    @ConditionalOnBooleanProperty("search.rag.jev.enabled")
    @ConditionalOnProperty(prefix = "spring.ai.typesafe", name = "api-key")
    public JevDocumentFilter jevDocumentFilter(TypeSafeClient typeSafeClient) {
        return JevDocumentFilter.builder(typeSafeClient).build();
    }

    @Bean
    @ConditionalOnProperty(name = "search.rag.rerank.provider", havingValue = "jev")
    @ConditionalOnBooleanProperty(name = "search.rag.rerank.enabled", matchIfMissing = true)
    @ConditionalOnProperty(prefix = "spring.ai.typesafe", name = "api-key")
    public JevDocumentReranker jevDocumentReranker(
            TypeSafeClient typeSafeClient,
            @Value("${search.rag.rerank.top-k:5}") int topK,
            @Value("${search.rag.jev.rerank.minimum-score:0.0}") double minimumScore) {
        return JevDocumentReranker.builder(typeSafeClient).topK(topK).minimumScore(minimumScore).build();
    }
}
