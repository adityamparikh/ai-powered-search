package dev.aparikh.aipoweredsearch.config;

import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.rag.JevDocumentFilter;
import org.springaicommunity.typesafe.rag.JevDocumentReranker;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The TypeSafe decisions provider ({@code search.rag.decisions.provider=typesafe}, the default).
 *
 * <p>A decisions provider answers narrow questions about a passage with calibrated probabilities
 * instead of generated text. It contributes up to two beans, by name, which {@link AiConfig} wires
 * without knowing the provider:
 *
 * <ul>
 *   <li>{@value AiConfig#DECISIONS_FILTER}, with {@code search.rag.decisions.enabled=true}: screens
 *       each retrieved passage before reranking. Here {@link JevDocumentFilter}, which drops
 *       passages that contain a prompt injection, are irrelevant, or lack answer evidence.
 *   <li>{@value AiConfig#DECISIONS_RERANKER}, with {@code search.rag.rerank.provider=decisions}:
 *       replaces the Claude reranker. Here {@link JevDocumentReranker}, which keeps the {@code
 *       search.rag.rerank.top-k} passages most likely to answer the question, discarding only those
 *       below {@code search.rag.decisions.rerank.minimum-score} (default 0, keep all).
 * </ul>
 *
 * <p>The client is the TypeSafe starter's {@link TypeSafeClient}: hosted Jev by default, or a local
 * model with the same API, such as Laya or Nimble, via {@code spring.ai.typesafe.base-url} and
 * {@code spring.ai.typesafe.model}. The starter only activates when {@code
 * spring.ai.typesafe.api-key} is set, so these beans carry the same condition. A local model needs
 * no real key, but it needs a placeholder.
 *
 * <p>Another provider, for example OpenAI's Decisions API, would be its own configuration
 * contributing beans with the same names under {@code search.rag.decisions.provider=<name>}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "search.rag.decisions.provider",
    havingValue = "typesafe",
    matchIfMissing = true)
@ConditionalOnProperty(prefix = "spring.ai.typesafe", name = "api-key")
public class TypeSafeDecisionsConfig {

  @Bean(AiConfig.DECISIONS_FILTER)
  @ConditionalOnBooleanProperty("search.rag.decisions.enabled")
  public DocumentPostProcessor typeSafeDecisionsFilter(TypeSafeClient typeSafeClient) {
    return JevDocumentFilter.builder(typeSafeClient).build();
  }

  @Bean(AiConfig.DECISIONS_RERANKER)
  @ConditionalOnProperty(name = "search.rag.rerank.provider", havingValue = "decisions")
  @ConditionalOnBooleanProperty(name = "search.rag.rerank.enabled", matchIfMissing = true)
  public DocumentPostProcessor typeSafeDecisionsReranker(
      TypeSafeClient typeSafeClient,
      @Value("${search.rag.rerank.top-k:5}") int topK,
      @Value("${search.rag.decisions.rerank.minimum-score:0.0}") double minimumScore) {
    return JevDocumentReranker.builder(typeSafeClient)
        .topK(topK)
        .minimumScore(minimumScore)
        .build();
  }
}
