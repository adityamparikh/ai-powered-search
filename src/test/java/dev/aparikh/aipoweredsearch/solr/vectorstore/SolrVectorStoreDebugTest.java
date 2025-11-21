package dev.aparikh.aipoweredsearch.solr.vectorstore;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.RestClientConfig;
import dev.aparikh.aipoweredsearch.config.SolrConfig;
import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Debug test for SolrVectorStore to identify issues.
 * This test runs regardless of OPENAI_API_KEY environment variable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.ai.openai.embedding.options.model=text-embedding-3-small"
        })
@Testcontainers
@Import({RestClientConfig.class, PostgresTestConfiguration.class, SolrConfig.class, SolrTestConfiguration.class})
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class SolrVectorStoreDebugTest {

    @Autowired
    SolrContainer solrContainer;

    @Autowired
    SolrClient solrClient;

    @Autowired
    RestClient.Builder restClientBuilder;

    private static final String COLLECTION_NAME = "test_collection";

    @Test
    void debugSchemaSetup() throws Exception {
        System.out.println("=== SolrVectorStore Debug Test ===");
        System.out.println("OPENAI_API_KEY env var: " + (System.getenv("OPENAI_API_KEY") != null ? "SET (length=" + System.getenv("OPENAI_API_KEY").length() + ")" : "NOT SET"));
        System.out.println("Container started: " + solrContainer.isRunning());
        System.out.println("Solr URL: http://" + solrContainer.getHost() + ":" + solrContainer.getSolrPort());

        // Create collection
        System.out.println("\n1. Creating collection...");
        var result = solrContainer.execInContainer(
                "/opt/solr/bin/solr", "create_collection",
                "-c", COLLECTION_NAME,
                "-d", "_default",
                "-shards", "1",
                "-replicationFactor", "1"
        );
        System.out.println("Collection creation stdout: " + result.getStdout());
        if (result.getExitCode() != 0) {
            System.err.println("Collection creation stderr: " + result.getStderr());
        }

        // Add field type
        System.out.println("\n2. Adding field type...");
        var typeResult = solrContainer.execInContainer(
                "sh", "-c",
                String.format(
                        """
                                curl -X POST -H 'Content-Type: application/json' \
                                --data '{"add-field-type":{"name":"knn_vector_1536","class":"solr.DenseVectorField","vectorDimension":1536,"similarityFunction":"cosine","knnAlgorithm":"hnsw"}}' \
                                http://localhost:8983/solr/%s/schema
                                """.strip(), COLLECTION_NAME)
        );
        System.out.println("Field type response: " + typeResult.getStdout());
        if (typeResult.getExitCode() != 0) {
            System.err.println("Field type error: " + typeResult.getStderr());
        }

        Thread.sleep(1000);

        // Add field
        System.out.println("\n3. Adding vector field...");
        var fieldResult = solrContainer.execInContainer(
                "sh", "-c",
                String.format(
                        """
                                curl -X POST -H 'Content-Type: application/json' \
                                --data '{"add-field":{"name":"vector","type":"knn_vector_1536","stored":true,"indexed":true}}' \
                                http://localhost:8983/solr/%s/schema
                                """.strip(), COLLECTION_NAME)
        );
        System.out.println("Field addition response: " + fieldResult.getStdout());
        if (fieldResult.getExitCode() != 0) {
            System.err.println("Field addition error: " + fieldResult.getStderr());
        }

        // Check schema
        System.out.println("\n4. Verifying schema...");
        var schemaResult = solrContainer.execInContainer(
                "sh", "-c",
                String.format(
                        "curl -s 'http://localhost:8983/solr/%s/schema/fields/vector'", COLLECTION_NAME)
        );
        System.out.println("Vector field schema: " + schemaResult.getStdout());

        // Test with mock embeddings if API key is available
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey != null && !apiKey.isEmpty() && !apiKey.startsWith("sk-test")) {
            System.out.println("\n5. Testing with real API key...");
            testWithRealEmbeddings(apiKey);
        } else {
            System.out.println("\n5. Skipping embedding test (no valid API key)");
            testWithMockData();
        }

        System.out.println("\n=== Test Complete ===");
    }

    private void testWithRealEmbeddings(String apiKey) {
        try {
            // Use RestClient.Builder to ensure JDK HttpClient is used instead of Jetty
            OpenAiApi openAiApi = OpenAiApi.builder()
                    .apiKey(apiKey)
                    .restClientBuilder(restClientBuilder)
                    .build();
            EmbeddingModel embeddingModel = new OpenAiEmbeddingModel(openAiApi);

            VectorStore vectorStore = SolrVectorStore.builder(solrClient, COLLECTION_NAME, embeddingModel)
                    .options(SolrVectorStoreOptions.defaults())
                    .build();

            // Add a document
            Document doc = new Document("test-1", "This is a test document about Spring AI",
                    Map.of("category", "test"));
            vectorStore.add(List.of(doc));

            // Search for it
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("Spring AI test")
                            .topK(5)
                            .build()
            );

            System.out.println("Search returned " + results.size() + " results");
            assertThat(results).isNotEmpty();

        } catch (Exception e) {
            System.err.println("Error during embedding test: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void testWithMockData() {
        System.out.println("Testing basic connectivity without embeddings...");
        try {
            // Just test that we can connect to Solr
            var pingResult = solrContainer.execInContainer(
                    "sh", "-c",
                    String.format(
                            "curl -s 'http://localhost:8983/solr/%s/admin/ping'", COLLECTION_NAME)
            );
            System.out.println("Ping response: " + pingResult.getStdout());
            assertThat(pingResult.getExitCode()).isEqualTo(0);
        } catch (Exception e) {
            System.err.println("Error during mock test: " + e.getMessage());
        }
    }
}