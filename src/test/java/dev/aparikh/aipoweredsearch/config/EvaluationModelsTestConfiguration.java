package dev.aparikh.aipoweredsearch.config;

import org.springframework.boot.devtools.restart.RestartScope;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared test configuration for the LLM judge used by evaluation tests.
 *
 * <p>Solr is deliberately <em>not</em> defined here. Evaluation tests import
 * {@link SolrTestConfiguration} alongside this class, which also points the application's
 * {@code solr.url} at the container, so the app under test and the test itself see the same
 * Solr. (An earlier version defined a second {@code solrContainer} bean here, which collided with
 * {@link SolrTestConfiguration} as soon as both were imported.)</p>
 *
 * <p>The Ollama container is {@link Lazy}: Testcontainers starts it only when a test actually asks
 * for it. That lets an evaluation run skip the judge, or use an Ollama server that already holds
 * the model ({@code -Drag.eval.ollama-url=http://localhost:11434}), without pulling the
 * multi-gigabyte model into a fresh container.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class EvaluationModelsTestConfiguration {

    /**
     * Model name for Ollama fact-checking evaluations.
     * <p>
     * Configuration based on user requirements:
     * - Model: bespoke-minicheck (specialized for fact-checking)
     * - numPredict: 2 (limit token generation for yes/no answers)
     * - temperature: 0.0 (deterministic output)
     */
    public static final String BESPOKE_MINICHECK = "bespoke-minicheck";

    /**
     * Creates a reusable Ollama container for LLM evaluations.
     *
     * <p>The bespoke-minicheck model must be pulled in test setup:
     * <pre>{@code
     * ollama.execInContainer("ollama", "pull", BESPOKE_MINICHECK);
     * }</pre>
     *
     * @return configured OllamaContainer instance
     */
    @Bean
    @Lazy
    @RestartScope
    public OllamaContainer ollamaContainer() {
        return new OllamaContainer(DockerImageName.parse("ollama/ollama:latest"))
                .withReuse(true);
    }
}
