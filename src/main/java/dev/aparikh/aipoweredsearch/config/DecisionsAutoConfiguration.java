package dev.aparikh.aipoweredsearch.config;

import dev.aparikh.aipoweredsearch.search.decision.DecisionDocumentFilter;
import dev.aparikh.aipoweredsearch.search.decision.DecisionDocumentReranker;
import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * The decisions stages of the RAG pipeline, on whatever {@link DecisionModel} a provider
 * contributes (see {@link TypeSafeDecisionsConfig}). {@link AiConfig} wires them by name:
 *
 * <ul>
 *   <li>{@value AiConfig#DECISIONS_FILTER}, with {@code search.rag.decisions.enabled=true}: a
 *       {@link DecisionDocumentFilter} that screens each retrieved passage before reranking and
 *       drops passages that contain a prompt injection, are irrelevant, or lack answer evidence.
 *   <li>{@value AiConfig#DECISIONS_RERANKER}, with {@code search.rag.rerank.provider=decisions}: a
 *       {@link DecisionDocumentReranker} that replaces the Claude reranker, keeping the {@code
 *       search.rag.rerank.top-k} passages most likely to answer the question and discarding only
 *       those below {@code search.rag.decisions.rerank.minimum-score} (default 0, keep all).
 * </ul>
 *
 * <p>An auto-configuration rather than a plain {@code @Configuration}, so that {@link
 * ConditionalOnBean} is evaluated after every application configuration has registered its beans. A
 * {@code DecisionModel} contributed by another auto-configuration is only seen if that
 * auto-configuration is ordered before this one ({@code @AutoConfiguration(before =
 * DecisionsAutoConfiguration.class)}).
 */
@AutoConfiguration
@ConditionalOnBean(DecisionModel.class)
public class DecisionsAutoConfiguration {

  @Bean(AiConfig.DECISIONS_FILTER)
  @ConditionalOnBooleanProperty("search.rag.decisions.enabled")
  public DocumentPostProcessor decisionsFilter(
      DecisionModel decisionModel,
      @Value("${search.rag.decisions.max-concurrency:8}") int maxConcurrency) {
    return DecisionDocumentFilter.builder(decisionModel).maxConcurrency(maxConcurrency).build();
  }

  @Bean(AiConfig.DECISIONS_RERANKER)
  @ConditionalOnProperty(name = "search.rag.rerank.provider", havingValue = "decisions")
  @ConditionalOnBooleanProperty(name = "search.rag.rerank.enabled", matchIfMissing = true)
  public DocumentPostProcessor decisionsReranker(
      DecisionModel decisionModel,
      @Value("${search.rag.rerank.top-k:5}") int topK,
      @Value("${search.rag.decisions.rerank.minimum-score:0.0}") double minimumScore,
      @Value("${search.rag.decisions.max-concurrency:8}") int maxConcurrency) {
    return DecisionDocumentReranker.builder(decisionModel)
        .topK(topK)
        .minimumScore(minimumScore)
        .maxConcurrency(maxConcurrency)
        .build();
  }
}
