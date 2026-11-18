package dev.aparikh.aipoweredsearch.limits;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/** Binding and validation of {@code search.limits.*}. */
class InputLimitsTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(LimitsConfig.class);

  @Test
  void defaults() {
    runner.run(
        context ->
            assertThat(context.getBean(InputLimits.class))
                .isEqualTo(new InputLimits(200, 1000, 4000, 100, 30_000)));
  }

  @Test
  void defaultsCoverTheDefaultKsAndTheRagRetrievalDepth() {
    runner.run(
        context -> {
          // Hybrid search defaults k to 100 and semantic search to 50; RAG retrieval
          // (search.rag.hybrid.top-k, also the ragEval harness's depth) uses 20.
          assertThat(context.getBean(InputLimits.class).maxK()).isGreaterThanOrEqualTo(100);
        });
  }

  @Test
  void bindsConfiguredValues() {
    runner
        .withPropertyValues(
            "search.limits.max-k=150",
            "search.limits.max-query-length=10",
            "search.limits.max-question-length=20",
            "search.limits.max-batch-size=5",
            "search.limits.max-document-length=50")
        .run(
            context ->
                assertThat(context.getBean(InputLimits.class))
                    .isEqualTo(new InputLimits(150, 10, 20, 5, 50)));
  }

  @Test
  void rejectsMaxKBelowTheHybridDefault() {
    runner
        .withPropertyValues("search.limits.max-k=99")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("maxK"));
  }

  @Test
  void rejectsNonPositiveLimits() {
    runner
        .withPropertyValues("search.limits.max-batch-size=0")
        .run(context -> assertThat(context).hasFailed());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(InputLimits.class)
  static class LimitsConfig {}
}
