package dev.aparikh.aipoweredsearch.config;

import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.rag.JevDocumentFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Optional TypeSafe Jev passage screening for RAG.
 *
 * <p>{@link JevDocumentFilter} screens each retrieved passage for prompt injection, contradiction of
 * the question's premise, relevance and answer evidence, and drops the ones that fail, before the
 * reranker runs. It is off by default: it sends the passages and the question to TypeSafe's hosted
 * API, one call per passage, which adds latency. A passage Jev cannot screen (the API errors or is
 * unreachable) is passed through with a WARN, so screening is best-effort.</p>
 *
 * <p>The filter exists only with {@code search.rag.jev.enabled=true} and a
 * {@code spring.ai.typesafe.api-key}. The key condition mirrors the TypeSafe starter's own, which
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
}
