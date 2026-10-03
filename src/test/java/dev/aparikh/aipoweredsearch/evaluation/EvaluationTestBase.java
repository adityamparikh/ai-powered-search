package dev.aparikh.aipoweredsearch.evaluation;

import dev.aparikh.aipoweredsearch.embedding.EmbeddingService;
import dev.aparikh.aipoweredsearch.fixtures.BookDatasetGenerator;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.solr.SolrContainer;

import java.util.List;
import java.util.Map;

import static dev.aparikh.aipoweredsearch.config.EvaluationModelsTestConfiguration.BESPOKE_MINICHECK;

/**
 * Base class for evaluation tests providing common setup for:
 * <ul>
 *   <li>an Ollama judge running bespoke-minicheck, wrapped in {@link FactCheckingEvaluator} and
 *       {@link RelevancyEvaluator};</li>
 *   <li>a Solr client and a books collection, by default the 1000-book synthetic dataset.</li>
 * </ul>
 *
 * <p>Subclasses import {@code SolrTestConfiguration} and {@code EvaluationModelsTestConfiguration}.
 * They customise the corpus by overriding {@link #createBooksCollection()} and {@link #loadBooks()},
 * and can opt out of the judge by overriding {@link #judgeEnabled()}.</p>
 *
 * <p>The judge runs in the lazily-started Ollama container unless the system property
 * {@value #OLLAMA_URL_PROPERTY} names an Ollama server to use instead. A long-lived local server
 * keeps the model between runs, where a fresh container pulls it every time.</p>
 */
public abstract class EvaluationTestBase {

    /** System property naming an external Ollama server for the judge. */
    public static final String OLLAMA_URL_PROPERTY = "rag.eval.ollama-url";

    @Autowired
    protected ObjectProvider<OllamaContainer> ollama;

    @Autowired
    protected SolrContainer solr;

    @Autowired(required = false)
    protected EmbeddingService embeddingService;

    /** Null when {@link #judgeEnabled()} is false. */
    protected @Nullable FactCheckingEvaluator factCheckingEvaluator;

    /** Null when {@link #judgeEnabled()} is false. */
    protected @Nullable RelevancyEvaluator relevancyEvaluator;

    /** The application's client, which {@code SolrTestConfiguration} points at the container. */
    @Autowired
    protected SolrClient solrClient;

    protected static final String BOOKS_COLLECTION = "books";

    @BeforeEach
    void setUpBase() throws Exception {
        if (judgeEnabled()) {
            setUpJudge();
        }

        createBooksCollection();
        loadBooks();
    }

    /**
     * Whether to start the Ollama judge. Defaults to true.
     */
    protected boolean judgeEnabled() {
        return true;
    }

    private void setUpJudge() throws Exception {
        String external = System.getProperty(OLLAMA_URL_PROPERTY);
        String baseUrl;
        if (external != null && !external.isBlank()) {
            baseUrl = external;
            System.out.println("Using external Ollama at " + baseUrl + "; pulling " + BESPOKE_MINICHECK + " if absent...");
            jdkRestClient(baseUrl).build()
                    .post().uri("/api/pull")
                    .body(Map.of("model", BESPOKE_MINICHECK, "stream", false))
                    .retrieve().toBodilessEntity();
        } else {
            OllamaContainer container = ollama.getObject();
            System.out.println("Pulling " + BESPOKE_MINICHECK + " model into the Ollama container...");
            container.execInContainer("ollama", "pull", BESPOKE_MINICHECK);
            baseUrl = container.getEndpoint();
        }

        RestClient.Builder restClientBuilder = jdkRestClient(baseUrl);

        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .build();

        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(BESPOKE_MINICHECK)
                .numPredict(2)  // Limit token generation for yes/no answers
                .temperature(0.0)  // Deterministic output
                .build();

        OllamaChatModel chatModel = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(options)
                .build();

        factCheckingEvaluator = FactCheckingEvaluator.builder(ChatClient.builder(chatModel)).build();
        relevancyEvaluator = RelevancyEvaluator.builder().chatClientBuilder(ChatClient.builder(chatModel)).build();
    }

    /** A RestClient on the JDK HttpClient (to avoid Jetty conflicts). */
    private static RestClient.Builder jdkRestClient(String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).requestFactory(new JdkClientHttpRequestFactory());
    }

    /**
     * Creates the books collection in Solr with proper vector field configuration.
     */
    protected void createBooksCollection() throws Exception {
        try {
            // Create collection
            solr.execInContainer(
                    "/opt/solr/bin/solr", "create_collection",
                    "-c", BOOKS_COLLECTION,
                    "-d", "_default"
            );

            Thread.sleep(2000); // Wait for collection creation

            // Add vector field type
            solr.execInContainer(
                    "curl", "-X", "POST",
                    "http://localhost:8983/solr/" + BOOKS_COLLECTION + "/schema",
                    "-H", "Content-Type: application/json",
                    "-d", """
                            {"add-field-type":{"name":"knn_vector_1536","class":"solr.DenseVectorField","vectorDimension":1536,"similarityFunction":"cosine","knnAlgorithm":"hnsw"}}
                            """.strip()
            );

            Thread.sleep(1000); // Wait for schema update

            // Add vector field
            solr.execInContainer(
                    "curl", "-X", "POST",
                    "http://localhost:8983/solr/" + BOOKS_COLLECTION + "/schema",
                    "-H", "Content-Type: application/json",
                    "-d", """
                            {"add-field":{"name":"vector","type":"knn_vector_1536","indexed":true,"stored":true}}
                            """.strip()
            );

            Thread.sleep(1000); // Wait for schema update

        } catch (Exception e) {
            // Collection might already exist
            System.out.println("Collection creation error (might already exist): " + e.getMessage());
        }
    }

    /**
     * Loads 1000 books into Solr with embeddings.
     */
    protected void loadBooks() throws Exception {
        List<BookDatasetGenerator.Book> books = BookDatasetGenerator.generate1000Books();

        System.out.println("Loading " + books.size() + " books into Solr...");

        for (BookDatasetGenerator.Book book : books) {
            SolrInputDocument doc = new SolrInputDocument();
            doc.addField("id", book.isbn);
            doc.addField("title", book.title);
            doc.addField("author", book.author);
            doc.addField("description", book.description);
            doc.addField("genre", book.genre);
            doc.addField("year", book.publicationYear);
            doc.addField("pages", book.pages);
            doc.addField("rating", book.rating);
            doc.addField("publisher", book.publisher);

            // Generate embedding from description if EmbeddingService is available
            if (embeddingService != null) {
                String vectorString = embeddingService.embedAndFormatForSolr(book.description);
                doc.addField("vector", vectorString);
            }

            solrClient.add(BOOKS_COLLECTION, doc);
        }

        // Commit all documents
        solrClient.commit(BOOKS_COLLECTION);

        System.out.println("Finished loading books into Solr");
    }

    /**
     * Cleanup method for subclasses to override if needed.
     */
    protected void cleanupAfterTest() throws Exception {
        if (solrClient != null) {
            try {
                solrClient.deleteByQuery(BOOKS_COLLECTION, "*:*");
                solrClient.commit(BOOKS_COLLECTION);
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
    }
}
