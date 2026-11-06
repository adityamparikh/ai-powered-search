package dev.aparikh.aipoweredsearch.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aparikh.aipoweredsearch.search.decision.DecisionDocumentFilter;
import dev.aparikh.aipoweredsearch.search.decision.DecisionDocumentReranker;
import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration;
import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.systemone.SystemOneDecisionModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The TypeSafe decisions provider contributes a {@link SystemOneDecisionModel} (Jev) when TypeSafe
 * is the selected provider (the default) and a TypeSafe API key is configured. {@link
 * DecisionsAutoConfiguration} builds the {@value AiConfig#DECISIONS_FILTER} and {@value
 * AiConfig#DECISIONS_RERANKER} beans on it only when they are switched on.
 */
class TypeSafeDecisionsConfigTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  TypeSafeAutoConfiguration.class, DecisionsAutoConfiguration.class))
          .withUserConfiguration(TypeSafeDecisionsConfig.class);

  @Test
  void decisionsEnabledWithAKeyCreatesTheDecisionFilterOnJev() {
    runner
        .withPropertyValues(
            "search.rag.decisions.enabled=true", "spring.ai.typesafe.api-key=test-key")
        .run(
            context -> {
              assertThat(context.getBean(DecisionModel.class))
                  .isInstanceOf(SystemOneDecisionModel.class);
              assertThat(context.getBean(AiConfig.DECISIONS_FILTER))
                  .isInstanceOf(DecisionDocumentFilter.class);
            });
  }

  @Test
  void theDecisionStagesAreRegisteredAsAnAutoConfiguration() {
    assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()))
        .contains(DecisionsAutoConfiguration.class.getName());
  }

  @Test
  void anyDecisionModelFeedsTheDecisionStages() {
    // Provider-agnostic: the stages need a DecisionModel, not TypeSafe.
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DecisionsAutoConfiguration.class))
        .withBean(
            DecisionModel.class,
            () ->
                prompt -> {
                  throw new UnsupportedOperationException();
                })
        .withPropertyValues(
            "search.rag.decisions.enabled=true", "search.rag.rerank.provider=decisions")
        .run(
            context -> {
              assertThat(context.getBean(AiConfig.DECISIONS_FILTER))
                  .isInstanceOf(DecisionDocumentFilter.class);
              assertThat(context.getBean(AiConfig.DECISIONS_RERANKER))
                  .isInstanceOf(DecisionDocumentReranker.class);
            });
  }

  @Test
  void offByDefaultEvenWithAKey() {
    runner
        .withPropertyValues("spring.ai.typesafe.api-key=test-key")
        .run(
            context -> {
              assertThat(context).hasSingleBean(DecisionModel.class);
              assertThat(context).doesNotHaveBean(AiConfig.DECISIONS_FILTER);
              assertThat(context).doesNotHaveBean(AiConfig.DECISIONS_RERANKER);
            });
  }

  @Test
  void enabledWithoutAKeyCreatesNothing() {
    runner
        .withPropertyValues("search.rag.decisions.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              // The starter only activates with a key, so there is no client either.
              assertThat(context).doesNotHaveBean(TypeSafeClient.class);
              assertThat(context).doesNotHaveBean(DecisionModel.class);
              assertThat(context).doesNotHaveBean(AiConfig.DECISIONS_FILTER);
            });
  }

  @Test
  void anotherDecisionsProviderLeavesTypeSafeOut() {
    runner
        .withPropertyValues(
            "search.rag.decisions.enabled=true",
            "search.rag.decisions.provider=openai",
            "search.rag.rerank.provider=decisions",
            "spring.ai.typesafe.api-key=test-key")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(AiConfig.DECISIONS_FILTER);
              assertThat(context).doesNotHaveBean(AiConfig.DECISIONS_RERANKER);
            });
  }

  @Test
  void theDecisionsRerankerIsSelectedWithTheRerankProviderAndAKey() {
    runner
        .withPropertyValues(
            "search.rag.rerank.provider=decisions", "spring.ai.typesafe.api-key=test-key")
        .run(
            context ->
                assertThat(context.getBean(AiConfig.DECISIONS_RERANKER))
                    .isInstanceOf(DecisionDocumentReranker.class));
  }

  @Test
  void noDecisionsRerankerWithoutAKeyOrWithRerankingOff() {
    runner
        .withPropertyValues("search.rag.rerank.provider=decisions")
        .run(context -> assertThat(context).doesNotHaveBean(AiConfig.DECISIONS_RERANKER));
    runner
        .withPropertyValues(
            "search.rag.rerank.provider=decisions",
            "spring.ai.typesafe.api-key=test-key",
            "search.rag.rerank.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(AiConfig.DECISIONS_RERANKER));
  }

  /** Like the application: application.properties is loaded, so its key mapping applies. */
  private final ApplicationContextRunner withApplicationProperties =
      runner.withInitializer(new ConfigDataApplicationContextInitializer());

  @Test
  void theTypeSafeApiKeyEnvironmentVariableEnablesTheProvider() {
    withApplicationProperties
        .withPropertyValues("TYPESAFE_API_KEY=test-key", "search.rag.decisions.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(TypeSafeClient.class);
              assertThat(context).hasBean(AiConfig.DECISIONS_FILTER);
            });
  }

  @Test
  void falseIsWhatAnUnsetKeyResolvesToAndKeepsTypeSafeOff() {
    // application.properties maps the key to ${TYPESAFE_API_KEY:false}. An empty default would
    // activate the starter and fail startup; "false" leaves its condition, and ours, unmatched.
    runner
        .withPropertyValues("spring.ai.typesafe.api-key=false", "search.rag.decisions.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(TypeSafeClient.class);
              assertThat(context).doesNotHaveBean(DecisionModel.class);
              assertThat(context).doesNotHaveBean(AiConfig.DECISIONS_FILTER);
            });
  }
}
