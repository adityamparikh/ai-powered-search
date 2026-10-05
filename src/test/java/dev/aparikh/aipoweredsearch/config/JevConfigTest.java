package dev.aparikh.aipoweredsearch.config;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration;
import org.springaicommunity.typesafe.rag.JevDocumentFilter;
import org.springaicommunity.typesafe.rag.JevDocumentReranker;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Jev filter and reranker exist only when switched on and a TypeSafe API key is configured.
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
                    // The starter only activates with a key, so there is no client either.
                    assertThat(context).doesNotHaveBean(TypeSafeClient.class);
                    assertThat(context).doesNotHaveBean(JevDocumentFilter.class);
                });
    }

    @Test
    void theJevRerankerIsSelectedWithTheProviderAndAKey() {
        runner.withPropertyValues("search.rag.rerank.provider=jev", "spring.ai.typesafe.api-key=test-key")
                .run(context -> assertThat(context).hasSingleBean(JevDocumentReranker.class));
    }

    @Test
    void noJevRerankerByDefaultOrWithoutAKeyOrWithRerankingOff() {
        runner.withPropertyValues("spring.ai.typesafe.api-key=test-key")
                .run(context -> assertThat(context).doesNotHaveBean(JevDocumentReranker.class));
        runner.withPropertyValues("search.rag.rerank.provider=jev")
                .run(context -> assertThat(context).doesNotHaveBean(JevDocumentReranker.class));
        runner.withPropertyValues("search.rag.rerank.provider=jev", "spring.ai.typesafe.api-key=test-key",
                        "search.rag.rerank.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(JevDocumentReranker.class));
    }
}
