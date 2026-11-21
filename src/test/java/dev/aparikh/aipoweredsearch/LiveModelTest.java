package dev.aparikh.aipoweredsearch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Marks a test class that calls a paid model API (Anthropic, OpenAI or TypeSafe) whenever its API
 * key is in the environment.
 *
 * <p>Tagged {@value #TAG}, so {@code ./gradlew test} and {@code ./gradlew build} never run it, even
 * with real keys exported. It runs only through {@code ./gradlew liveTest -PallowBilledCalls=true}.
 * Keep the class's own {@code @EnabledIfEnvironmentVariable} key check as well, so a live run skips
 * the classes whose key is missing. {@code BilledTestsAreOptInTest} fails the build if a class
 * gated on an API key is missing this annotation.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag(LiveModelTest.TAG)
public @interface LiveModelTest {

  /** The JUnit tag the Gradle {@code test} task excludes and {@code liveTest} includes. */
  String TAG = "live";
}
