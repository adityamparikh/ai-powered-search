package dev.aparikh.aipoweredsearch.config;

import org.springframework.boot.devtools.restart.RestartScope;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.solr.SolrContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared test configuration for Ollama and Solr containers used across evaluation tests.
 *
 * <p>This configuration provides reusable container instances with @RestartScope to avoid
 * recreating containers for every test class, significantly improving test execution speed.
 *
 * <p>Containers are configured for:
 * <ul>
 *   <li>Ollama: For LLM-based evaluation (fact-checking, relevancy)</li>
 *   <li>Solr 9.10.0: For search operations with native RRF support</li>
 * </ul>
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
     * <p>Container configuration:
     * <ul>
     *   <li>Image: ollama/ollama:latest</li>
     *   <li>Reuse: enabled for faster test execution</li>
     *   <li>Scope: @RestartScope for sharing across test classes</li>
     * </ul>
     *
     * <p>The bespoke-minicheck model must be pulled in test setup:
     * <pre>{@code
     * ollama.execInContainer("ollama", "pull", BESPOKE_MINICHECK);
     * }</pre>
     *
     * @return configured OllamaContainer instance
     */
    @Bean
    @RestartScope
    @ServiceConnection
    public OllamaContainer ollamaContainer() {
        return new OllamaContainer(DockerImageName.parse("ollama/ollama:latest"))
                .withReuse(true);
    }

    /**
     * Creates a reusable Solr container for search operations.
     *
     * <p>Container configuration:
     * <ul>
     *   <li>Image: solr:9.10.0 (with native RRF support)</li>
     *   <li>Heap: 512m (sufficient for evaluation tests)</li>
     *   <li>Reuse: enabled for faster test execution</li>
     *   <li>Scope: @RestartScope for sharing across test classes</li>
     * </ul>
     *
     * <p>Collections with vector fields must be created in test setup.
     *
     * @return configured SolrContainer instance
     */
    @Bean
    @RestartScope
    public SolrContainer solrContainer() {
        return new SolrContainer(DockerImageName.parse("solr:9.10.0"))
                .withEnv("SOLR_HEAP", "512m")
                .withReuse(true);
    }
}
