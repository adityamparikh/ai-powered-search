package dev.aparikh.aipoweredsearch.limits;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/** Binding and validation of {@code search.limits.*}, and how each limit measures its value. */
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
                .isEqualTo(new InputLimits(200, 1000, 4000, 100, 150_000, 30_000, 512, 10_000)));
  }

  @Test
  void maxKFloorIsTheLargestEndpointDefault() {
    // The defaults SearchService applies when a request leaves k out must stay within the cap.
    assertThat(InputLimits.LARGEST_DEFAULT_K)
        .isGreaterThanOrEqualTo(InputLimits.DEFAULT_SEMANTIC_K)
        .isGreaterThanOrEqualTo(InputLimits.DEFAULT_HYBRID_K)
        .isIn(InputLimits.DEFAULT_SEMANTIC_K, InputLimits.DEFAULT_HYBRID_K);
  }

  @Test
  void bindsConfiguredValues() {
    runner
        .withPropertyValues(
            "search.limits.max-k=150",
            "search.limits.max-query-length=10",
            "search.limits.max-question-length=20",
            "search.limits.max-batch-size=5",
            "search.limits.max-batch-content-length=40",
            "search.limits.max-document-length=50",
            "search.limits.max-id-length=60",
            "search.limits.max-metadata-length=70")
        .run(
            context ->
                assertThat(context.getBean(InputLimits.class))
                    .isEqualTo(new InputLimits(150, 10, 20, 5, 40, 50, 60, 70)));
  }

  @Test
  void rejectsMaxKBelowTheLargestDefaultK() {
    runner
        .withPropertyValues("search.limits.max-k=" + (InputLimits.LARGEST_DEFAULT_K - 1))
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
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("maxBatchSize"));
  }

  @Test
  void lengthsAreCodePoints() {
    assertThat(InputLimit.QUERY_LENGTH.measure("😀😀")).isEqualTo(2);
  }

  @Test
  void batchContentSumsTheContentOfEachElement() {
    List<EmbeddedContent> batch = List.of(() -> "abc", () -> null, () -> "😀");
    assertThat(InputLimit.BATCH_CONTENT_LENGTH.measure(batch)).isEqualTo(4);
  }

  @Test
  void metadataCountsNestedKeysAndValues() {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("tags", List.of("java", "ai")); // 4 + (1 + 4 + 2)
    metadata.put("year", 2024); // 4 + 4
    metadata.put("n", null); // 1 + 1
    // 1 for the map itself
    assertThat(InputLimit.METADATA_LENGTH.measure(metadata)).isEqualTo(1 + 11 + 8 + 2);
  }

  @Test
  void metadataOfEmptyContainersIsStillBounded() {
    List<Object> empties = new ArrayList<>(Arrays.asList(new Object[1000]));
    empties.replaceAll(ignored -> List.of());
    // 1 for the outer list and 1 for each empty one
    assertThat(InputLimit.METADATA_LENGTH.measure(Map.of("k", empties))).isEqualTo(1 + 1 + 1001);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(InputLimits.class)
  static class LimitsConfig {}
}
