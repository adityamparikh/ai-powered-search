package dev.aparikh.aipoweredsearch.config;

import io.micrometer.observation.ObservationRegistry;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.systemone.SystemOneDecisionModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The TypeSafe decisions provider ({@code search.rag.decisions.provider=typesafe}, the default):
 * TypeSafe's Jev behind Spring AI's {@link DecisionModel} abstraction.
 *
 * <p>A decisions provider contributes one bean, a {@link DecisionModel}. {@link
 * DecisionsAutoConfiguration} turns whichever {@code DecisionModel} is present into the RAG stages
 * (the passage filter and the decisions reranker), so this class knows nothing about RAG and the
 * stages know nothing about TypeSafe.
 *
 * <p>Here the model is Spring AI's {@link SystemOneDecisionModel} on the TypeSafe starter's {@link
 * TypeSafeClient}: hosted Jev by default, or a local model with the same API, such as Laya or
 * Nimble, via {@code spring.ai.typesafe.base-url} and {@code spring.ai.typesafe.model}. The starter
 * only activates when {@code spring.ai.typesafe.api-key} is set, so this configuration carries the
 * same condition. A local model needs no real key, but it needs a placeholder.
 *
 * <p>Another provider would be its own configuration contributing a {@code DecisionModel} under
 * {@code search.rag.decisions.provider=<name>}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "search.rag.decisions.provider",
    havingValue = "typesafe",
    matchIfMissing = true)
@ConditionalOnProperty(prefix = "spring.ai.typesafe", name = "api-key")
public class TypeSafeDecisionsConfig {

  @Bean
  public SystemOneDecisionModel decisionModel(
      TypeSafeClient typeSafeClient, ObjectProvider<ObservationRegistry> observationRegistry) {
    return SystemOneDecisionModel.builder(typeSafeClient)
        .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
        .build();
  }
}
