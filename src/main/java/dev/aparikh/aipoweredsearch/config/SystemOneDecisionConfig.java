package dev.aparikh.aipoweredsearch.config;

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.systemone.SystemOneDecisionModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The {@link DecisionModel} behind {@code search.rag.decisions.enabled} (passage screening) and
 * {@code search.rag.rerank.provider=decisions} (reranking), backed by System One: TypeSafe's hosted
 * Jev, or a local model that speaks the same API (Laya, llmman, Ollama).
 *
 * <p>Spring AI ships no starter for System One, so this builds the model by hand. It follows the
 * convention of Spring AI's own model auto-configurations: {@code spring.ai.model.decision} selects
 * the decision model, and {@code none} switches it off. To use another provider's model (for
 * example the Ollama starter's), set {@code spring.ai.model.decision} to that provider so this one
 * steps aside; otherwise this model, being configured here, is registered first and the starter's
 * backs off. {@link AiConfig} builds the filter and reranker from whichever {@link DecisionModel}
 * is present.
 *
 * <p>It needs {@code search.rag.decisions.systemone.api-key}. A local model needs only a
 * placeholder, plus a base URL and model name for that backend.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "spring.ai.model.decision",
    havingValue = "systemone",
    matchIfMissing = true)
@ConditionalOnProperty(prefix = "search.rag.decisions.systemone", name = "api-key")
@EnableConfigurationProperties(SystemOneDecisionConfig.Properties.class)
public class SystemOneDecisionConfig {

  /**
   * {@code search.rag.decisions.systemone.*}. Unset {@code base-url} and {@code model} mean the
   * client's own defaults: hosted Jev, model {@code jev-latest}.
   *
   * @param apiKey the TypeSafe key; any placeholder for a local model
   * @param baseUrl the API base URL, for a local backend such as Laya, llmman or Ollama
   * @param model the model name; local backends have no {@code jev-latest}, so set it for them
   * @param timeout how long one attempt may take
   * @param provider the provider name recorded on observations
   */
  @ConfigurationProperties(prefix = "search.rag.decisions.systemone")
  public record Properties(
      String apiKey,
      @DefaultValue("") String baseUrl,
      @DefaultValue("") String model,
      @DefaultValue("10s") Duration timeout,
      @DefaultValue("systemone") String provider) {}

  @Bean
  @ConditionalOnMissingBean(DecisionModel.class)
  public DecisionModel systemOneDecisionModel(
      Properties properties, ObjectProvider<ObservationRegistry> observationRegistry) {
    TypeSafeClient.Builder client =
        TypeSafeClient.builder().apiKey(properties.apiKey()).timeout(properties.timeout());
    if (!properties.baseUrl().isBlank()) {
      client.baseUrl(properties.baseUrl());
    }
    if (!properties.model().isBlank()) {
      client.defaultModel(properties.model());
    }
    return SystemOneDecisionModel.builder(client.build())
        .provider(properties.provider())
        .observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
        .build();
  }
}
