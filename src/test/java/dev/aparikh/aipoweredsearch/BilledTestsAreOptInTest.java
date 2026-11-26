package dev.aparikh.aipoweredsearch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.platform.commons.support.AnnotationSupport;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * Guards the rule that no billed model call runs unless someone asked for it.
 *
 * <p>A test class enabled by a model API key ({@code ANTHROPIC_API_KEY}, {@code OPENAI_API_KEY} or
 * {@code TYPESAFE_API_KEY}) calls that paid API whenever the key is exported, and developers
 * usually have their keys exported. Such a class must carry a tag that {@code ./gradlew test}
 * excludes: {@link LiveModelTest} ({@code live}) or {@code rag-eval}. Then only {@code ./gradlew
 * liveTest -PallowBilledCalls=true} or {@code ./gradlew ragEval -PallowBilledCalls=true} run it.
 *
 * <p>The scan covers class-level and method-level {@code @EnabledIfEnvironmentVariable}. It is a
 * lint, not the only guard: the {@code test} task also strips the keys from the test JVM's
 * environment, so a key read some other way still finds nothing.
 */
class BilledTestsAreOptInTest {

  private static final Set<String> MODEL_API_KEYS =
      Set.of("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "TYPESAFE_API_KEY");

  /** Tags excluded from the Gradle {@code test} task in {@code build.gradle.kts}. */
  private static final Set<String> OPT_IN_TAGS = Set.of(LiveModelTest.TAG, "rag-eval");

  @Test
  void everyTestClassGatedOnAModelApiKeyIsExcludedFromTheDefaultTestRun() throws Exception {
    List<Class<?>> gated = testClasses().filter(this::isGatedOnAModelApiKey).toList();

    // The scan must actually find the key-gated classes, or this test proves nothing.
    assertThat(gated)
        .extracting(Class::getSimpleName)
        .contains(
            "PromptCacheMetricsAdvisorIT", "SolrVectorStoreIT", "RagEvaluationIT", "MethodGated");
    assertThat(gated)
        .as("classes gated on a model API key without an opt-in tag (add @LiveModelTest)")
        .filteredOn(type -> !hasOptInTag(type))
        .isEmpty();
  }

  @Test
  void liveModelTestCarriesTheTagTheBuildExcludes() {
    assertThat(AnnotationSupport.findRepeatableAnnotations(Marked.class, Tag.class))
        .extracting(Tag::value)
        .containsExactly("live");
  }

  @LiveModelTest
  private static final class Marked {}

  /** Gated on a key at method level only: the scan must still find it. Tagged, so it passes. */
  @LiveModelTest
  private static final class MethodGated {

    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
    void callsAPaidModel() {
      // Intentionally empty: a fixture for the scan, never run. Only its annotation matters.
    }
  }

  /** True when the class, or any method it declares, is enabled by a model API key. */
  private boolean isGatedOnAModelApiKey(Class<?> type) {
    if (isGatedOnAModelApiKey(
        AnnotationSupport.findRepeatableAnnotations(type, EnabledIfEnvironmentVariable.class))) {
      return true;
    }
    final Method[] methods;
    try {
      methods = type.getDeclaredMethods();
    } catch (LinkageError e) {
      throw new IllegalStateException("Cannot inspect methods of " + type.getName(), e);
    }
    return Arrays.stream(methods)
        .anyMatch(
            method ->
                isGatedOnAModelApiKey(
                    AnnotationSupport.findRepeatableAnnotations(
                        method, EnabledIfEnvironmentVariable.class)));
  }

  private static boolean isGatedOnAModelApiKey(List<EnabledIfEnvironmentVariable> conditions) {
    return conditions.stream().anyMatch(condition -> MODEL_API_KEYS.contains(condition.named()));
  }

  private static boolean hasOptInTag(Class<?> type) {
    return AnnotationSupport.findRepeatableAnnotations(type, Tag.class).stream()
        .map(Tag::value)
        .anyMatch(OPT_IN_TAGS::contains);
  }

  private static Stream<Class<?>> testClasses() throws IOException {
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    MetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
    Resource[] resources =
        resolver.getResources("classpath*:dev/aparikh/aipoweredsearch/**/*.class");
    List<Class<?>> classes = new ArrayList<>();
    for (Resource resource : Arrays.asList(resources)) {
      String name = readers.getMetadataReader(resource).getClassMetadata().getClassName();
      try {
        classes.add(Class.forName(name, false, BilledTestsAreOptInTest.class.getClassLoader()));
      } catch (ClassNotFoundException | LinkageError e) {
        throw new IllegalStateException("Cannot load " + name, e);
      }
    }
    return classes.stream();
  }
}
