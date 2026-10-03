package dev.aparikh.aipoweredsearch.config;

import dev.aparikh.aipoweredsearch.AiPoweredSearchApplication;
import dev.aparikh.aipoweredsearch.search.RerankingDocumentPostProcessor;
import dev.aparikh.aipoweredsearch.search.rag.ObservedDocumentPostProcessor;
import dev.aparikh.aipoweredsearch.search.rag.RagPostProcessors;
import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * W4: which post-processors {@link RagPostProcessingConfig} assembles for each property combination,
 * and that nothing about TypeSafe is required at startup.
 */
class RagPostProcessingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // Boot's conversion service, as in the running app, so "5s" binds to a Duration.
            .withInitializer(context -> context.getBeanFactory().setConversionService(new ApplicationConversionService()))
            .withUserConfiguration(RagPostProcessingConfig.class)
            .withBean(RerankingDocumentPostProcessor.class,
                    () -> new RerankingDocumentPostProcessor(mock(ChatClient.class), 5));

    private static List<String> chain(org.springframework.context.ApplicationContext context) {
        return context.getBean(RagPostProcessors.class).processors().stream()
                .map(p -> ((ObservedDocumentPostProcessor) p).processorName())
                .toList();
    }

    @Test
    void defaultsRerankWithClaudeOnly() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(chain(context)).containsExactly("RerankingDocumentPostProcessor");
        });
    }

    @Test
    void rerankingDisabledMeansNoPostProcessors() {
        runner.withPropertyValues("search.rag.rerank.enabled=false")
                .run(context -> assertThat(chain(context)).isEmpty());
    }

    @Test
    void jevFilterRunsBeforeTheRerankerWhenEnabledWithAKey() {
        runner.withPropertyValues("search.rag.jev.enabled=true", "spring.ai.typesafe.api-key=test-key")
                .run(context -> assertThat(chain(context)).containsExactly("JevDocumentFilter", "RerankingDocumentPostProcessor"));
    }

    @Test
    void jevEnabledWithoutAKeyDegradesToTheRerankerAlone() {
        runner.withPropertyValues("search.rag.jev.enabled=true", "spring.ai.typesafe.api-key=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(chain(context)).containsExactly("RerankingDocumentPostProcessor");
                });
    }

    @Test
    void jevRerankerReplacesClaudeWhenSelected() {
        runner.withPropertyValues("search.rag.rerank.provider=jev", "spring.ai.typesafe.api-key=test-key")
                .run(context -> assertThat(chain(context)).containsExactly("JevDocumentReranker"));
    }

    @Test
    void jevFilterAndJevReranker() {
        runner.withPropertyValues("search.rag.jev.enabled=true", "search.rag.rerank.provider=jev",
                        "spring.ai.typesafe.api-key=test-key")
                .run(context -> assertThat(chain(context)).containsExactly("JevDocumentFilter", "JevDocumentReranker"));
    }

    @Test
    void jevProviderWithoutAKeyFallsBackToClaude() {
        runner.withPropertyValues("search.rag.rerank.provider=JEV")
                .run(context -> assertThat(chain(context)).containsExactly("RerankingDocumentPostProcessor"));
    }

    @Test
    void anUnknownProviderFailsFast() {
        runner.withPropertyValues("search.rag.rerank.provider=cohere")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("search.rag.rerank.provider"));
    }

    @Test
    void anUnusableJevTimeoutOrConcurrencyFailsFast() {
        runner.withPropertyValues("search.rag.jev.timeout=0s")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("search.rag.jev.timeout"));
        runner.withPropertyValues("search.rag.jev.concurrency=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("concurrency"));
    }

    @Test
    void theStarterAutoConfigurationWouldFailOnAnEmptyKeySoItIsExcluded() {
        // W0 finding A2: an empty spring.ai.typesafe.api-key, which is what ${TYPESAFE_API_KEY:}
        // resolves to without the env var, breaks TypeSafeAutoConfiguration. The application class
        // excludes it (an annotation exclude is not replaced by a spring.autoconfigure.exclude set
        // elsewhere), and RagPostProcessingConfig builds the client only when needed.
        EnableAutoConfiguration autoConfiguration = AnnotatedElementUtils.findMergedAnnotation(
                AiPoweredSearchApplication.class, EnableAutoConfiguration.class);
        assertThat(autoConfiguration).isNotNull();
        assertThat(autoConfiguration.exclude()).contains(TypeSafeAutoConfiguration.class);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TypeSafeAutoConfiguration.class))
                .withPropertyValues("spring.ai.typesafe.api-key=")
                .run(context -> assertThat(context).hasFailed());

        runner.withPropertyValues("spring.ai.typesafe.api-key=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TypeSafeClient.class);
                });
    }

    @Test
    void typeSafeClientIsBuiltOnlyWithAKey() {
        assertThat(RagPostProcessingConfig.typeSafeClient(" ", "", java.time.Duration.ofSeconds(1))).isNull();
        assertThat(RagPostProcessingConfig.typeSafeClient("key", "http://127.0.0.1:9", java.time.Duration.ofSeconds(1)))
                .isNotNull();
    }
}
