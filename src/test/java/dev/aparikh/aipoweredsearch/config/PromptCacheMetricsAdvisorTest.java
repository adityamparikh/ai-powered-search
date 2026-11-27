package dev.aparikh.aipoweredsearch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class PromptCacheMetricsAdvisorTest {

  @Test
  void savingsMatchTheDocumentedExample() {
    // 1000 cached + 100 regular: (1100 - 200) / 1100 ≈ 81.8%
    assertThat(PromptCacheMetricsAdvisor.calculateSavings(1000, 100))
        .isCloseTo(81.818, within(0.001));
  }

  @Test
  void allCachedInputSavesNinetyPercent() {
    assertThat(PromptCacheMetricsAdvisor.calculateSavings(2048, null))
        .isCloseTo(90.0, within(1e-9));
  }

  @Test
  void noCacheReadsMeansNoSavings() {
    assertThat(PromptCacheMetricsAdvisor.calculateSavings(0, 256)).isZero();
    assertThat(PromptCacheMetricsAdvisor.calculateSavings(0, null)).isZero();
  }

  @Test
  void advisorIdentityIsUnchanged() {
    PromptCacheMetricsAdvisor advisor = PromptCacheMetricsAdvisor.builder().build();

    assertThat(advisor.getName()).isEqualTo("PromptCacheMetricsAdvisor");
    assertThat(advisor.getOrder()).isZero();
  }
}
