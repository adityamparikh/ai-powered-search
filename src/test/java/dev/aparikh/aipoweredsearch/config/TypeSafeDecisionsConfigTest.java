package dev.aparikh.aipoweredsearch.config;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration;
import org.springaicommunity.typesafe.rag.JevDocumentFilter;
import org.springaicommunity.typesafe.rag.JevDocumentReranker;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The TypeSafe decisions provider contributes the {@value AiConfig#DECISIONS_FILTER} and
 * {@value AiConfig#DECISIONS_RERANKER} beans only when they are switched on, TypeSafe is the
 * selected provider (the default), and a TypeSafe API key is configured.
 */
class TypeSafeDecisionsConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TypeSafeAutoConfiguration.class))
            .withUserConfiguration(TypeSafeDecisionsConfig.class);

    @Test
    void decisionsEnabledWithAKeyCreatesTheJevFilter() {
        runner.withPropertyValues("search.rag.decisions.enabled=true", "spring.ai.typesafe.api-key=test-key")
                .run(context -> assertThat(context.getBean(AiConfig.DECISIONS_FILTER)).isInstanceOf(JevDocumentFilter.class));
    }

    @Test
    void offByDefaultEvenWithAKey() {
        runner.withPropertyValues("spring.ai.typesafe.api-key=test-key")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AiConfig.DECISIONS_FILTER);
                    assertThat(context).doesNotHaveBean(AiConfig.DECISIONS_RERANKER);
                });
    }

    @Test
    void enabledWithoutAKeyCreatesNothing() {
        runner.withPropertyValues("search.rag.decisions.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // The starter only activates with a key, so there is no client either.
                    assertThat(context).doesNotHaveBean(TypeSafeClient.class);
                    assertThat(context).doesNotHaveBean(AiConfig.DECISIONS_FILTER);
                });
    }

    @Test
    void anotherDecisionsProviderLeavesTypeSafeOut() {
        runner.withPropertyValues("search.rag.decisions.enabled=true", "search.rag.decisions.provider=openai",
                        "search.rag.rerank.provider=decisions", "spring.ai.typesafe.api-key=test-key")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AiConfig.DECISIONS_FILTER);
                    assertThat(context).doesNotHaveBean(AiConfig.DECISIONS_RERANKER);
                });
    }

    @Test
    void theDecisionsRerankerIsSelectedWithTheRerankProviderAndAKey() {
        runner.withPropertyValues("search.rag.rerank.provider=decisions", "spring.ai.typesafe.api-key=test-key")
                .run(context -> assertThat(context.getBean(AiConfig.DECISIONS_RERANKER)).isInstanceOf(JevDocumentReranker.class));
    }

    @Test
    void noDecisionsRerankerWithoutAKeyOrWithRerankingOff() {
        runner.withPropertyValues("search.rag.rerank.provider=decisions")
                .run(context -> assertThat(context).doesNotHaveBean(AiConfig.DECISIONS_RERANKER));
        runner.withPropertyValues("search.rag.rerank.provider=decisions", "spring.ai.typesafe.api-key=test-key",
                        "search.rag.rerank.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AiConfig.DECISIONS_RERANKER));
    }

    /** Like the application: application.properties is loaded, so its key mapping applies. */
    private final ApplicationContextRunner withApplicationProperties = runner
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void theTypeSafeApiKeyEnvironmentVariableEnablesTheProvider() {
        withApplicationProperties.withPropertyValues("TYPESAFE_API_KEY=test-key", "search.rag.decisions.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(TypeSafeClient.class);
                    assertThat(context).hasBean(AiConfig.DECISIONS_FILTER);
                });
    }

    @Test
    void falseIsWhatAnUnsetKeyResolvesToAndKeepsTypeSafeOff() {
        // application.properties maps the key to ${TYPESAFE_API_KEY:false}. An empty default would
        // activate the starter and fail startup; "false" leaves its condition, and ours, unmatched.
        runner.withPropertyValues("spring.ai.typesafe.api-key=false", "search.rag.decisions.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TypeSafeClient.class);
                    assertThat(context).doesNotHaveBean(AiConfig.DECISIONS_FILTER);
                });
    }
}
