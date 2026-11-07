package dev.aparikh.aipoweredsearch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.systemone.SystemOneDecisionModel;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The System One {@link DecisionModel} exists only when System One is the selected decision model
 * (the default, or {@code spring.ai.model.decision=systemone}) and an API key is configured. {@code
 * spring.ai.model.decision=none} is the opt-out, as for Spring AI's own models.
 */
class SystemOneDecisionConfigTest {

  private static final String KEY = "search.rag.decisions.systemone.api-key=test-key";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(SystemOneDecisionConfig.class);

  @Test
  void aKeyCreatesTheSystemOneDecisionModel() {
    runner
        .withPropertyValues(KEY)
        .run(
            context ->
                assertThat(context.getBean(DecisionModel.class))
                    .isInstanceOf(SystemOneDecisionModel.class));
  }

  @Test
  void noKeyCreatesNothing() {
    runner.run(context -> assertThat(context).doesNotHaveBean(DecisionModel.class));
  }

  @Test
  void noneIsTheOptOutEvenWithAKey() {
    runner
        .withPropertyValues(KEY, "spring.ai.model.decision=none")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(DecisionModel.class);
            });
  }

  @Test
  void selectingAnotherProviderLeavesSystemOneOut() {
    runner
        .withPropertyValues(KEY, "spring.ai.model.decision=ollama")
        .run(context -> assertThat(context).doesNotHaveBean(DecisionModel.class));
  }

  @Test
  void systemoneCanBeSelectedExplicitly() {
    runner
        .withPropertyValues(KEY, "spring.ai.model.decision=systemone")
        .run(context -> assertThat(context).hasSingleBean(SystemOneDecisionModel.class));
  }

  @Test
  void aLocalBackendNeedsOnlyABaseUrlAndAModel() {
    runner
        .withPropertyValues(
            KEY,
            "search.rag.decisions.systemone.base-url=http://localhost:11434",
            "search.rag.decisions.systemone.model=d1-omni:600m",
            "search.rag.decisions.systemone.provider=ollama")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(SystemOneDecisionModel.class);
            });
  }

  @Test
  void anotherProviderIsSelectedWithTheModelProperty() {
    runner
        .withUserConfiguration(OtherProvider.class)
        .withPropertyValues(KEY, "spring.ai.model.decision=other")
        .run(
            context -> {
              assertThat(context).hasSingleBean(DecisionModel.class);
              assertThat(context.getBean(DecisionModel.class))
                  .isSameAs(context.getBean("otherProviderDecisionModel"));
            });
  }

  /** Like the application: application.properties is loaded, so its key mapping applies. */
  private final ApplicationContextRunner withApplicationProperties =
      runner.withInitializer(new ConfigDataApplicationContextInitializer());

  @Test
  void theTypeSafeApiKeyEnvironmentVariableEnablesTheModel() {
    withApplicationProperties
        .withPropertyValues("TYPESAFE_API_KEY=test-key")
        .run(context -> assertThat(context).hasSingleBean(SystemOneDecisionModel.class));
  }

  @Test
  void falseIsWhatAnUnsetKeyResolvesToAndKeepsSystemOneOff() {
    // application.properties maps the key to ${TYPESAFE_API_KEY:false}, so an unset variable is the
    // value "false", which ConditionalOnProperty treats as off. Set directly so the test does not
    // depend on whether the host has TYPESAFE_API_KEY exported.
    runner
        .withPropertyValues("search.rag.decisions.systemone.api-key=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(DecisionModel.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class OtherProvider {
    @Bean
    DecisionModel otherProviderDecisionModel() {
      return mock(DecisionModel.class);
    }
  }
}
