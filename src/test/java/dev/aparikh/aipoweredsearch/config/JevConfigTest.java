package dev.aparikh.aipoweredsearch.config;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration;
import org.springaicommunity.typesafe.rag.JevDocumentFilter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Jev filter exists only when it is switched on and a TypeSafe API key is configured.
 */
class JevConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TypeSafeAutoConfiguration.class))
            .withUserConfiguration(JevConfig.class);

    @Test
    void enabledWithAKeyCreatesTheFilter() {
        runner.withPropertyValues("search.rag.jev.enabled=true", "spring.ai.typesafe.api-key=test-key")
                .run(context -> assertThat(context).hasSingleBean(JevDocumentFilter.class));
    }

    @Test
    void offByDefaultEvenWithAKey() {
        runner.withPropertyValues("spring.ai.typesafe.api-key=test-key")
                .run(context -> assertThat(context).doesNotHaveBean(JevDocumentFilter.class));
    }

    @Test
    void enabledWithoutAKeyCreatesNothing() {
        runner.withPropertyValues("search.rag.jev.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(JevDocumentFilter.class);
                });
    }
}
