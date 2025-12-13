package dev.aparikh.aipoweredsearch.evaluation;

import dev.aparikh.aipoweredsearch.embedding.EmbeddingService;
import dev.aparikh.aipoweredsearch.fixtures.BookDatasetGenerator;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.solr.SolrContainer;

import java.util.List;

import static dev.aparikh.aipoweredsearch.config.EvaluationModelsTestConfiguration.BESPOKE_MINICHECK;

/**
 * Base class for all evaluation tests providing common setup for:
 * - Ollama container with bespoke-minicheck model
 * - Solr container with 1000 book dataset
 * - FactCheckingEvaluator and RelevancyEvaluator
 *
 * <p>This class centralizes the setup logic to avoid duplication across evaluation test classes.
 */
public abstract class EvaluationTestBase {

    @Autowired
    protected OllamaContainer ollama;

    @Autowired
    protected SolrContainer solr;

    @Autowired(required = false)
    protected EmbeddingService embeddingService;

    protected FactCheckingEvaluator factCheckingEvaluator;
    protected RelevancyEvaluator relevancyEvaluator;
    protected SolrClient solrClient;

    protected static final String BOOKS_COLLECTION = "books";

    @BeforeEach
    void setUpBase() throws Exception {
        // 1. Pull bespoke-minicheck model
        System.out.println("Pulling " + BESPOKE_MINICHECK + " model...");
        ollama.execInContainer("ollama", "pull", BESPOKE_MINICHECK);

        // 2. Create Ollama API with JDK HttpClient (to avoid Jetty conflicts)
        String baseUrl = ollama.getEndpoint();
        RestClient.Builder restClientBuilder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory());

        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .build();

        // 3. Create ChatModel with user-specified config
        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(BESPOKE_MINICHECK)
                .numPredict(2)  // Limit token generation for yes/no answers
                .temperature(0.0)  // Deterministic output
                .build();

        OllamaChatModel chatModel = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(options)
                .build();

        // 4. Create evaluators
        factCheckingEvaluator = FactCheckingEvaluator.builder(ChatClient.builder(chatModel)).build();
        relevancyEvaluator = RelevancyEvaluator.builder().chatClientBuilder(ChatClient.builder(chatModel)).build();

        // 5. Create Solr client
        String solrUrl = "http://" + solr.getHost() + ":" + solr.getSolrPort() + "/solr";
        solrClient = new Http2SolrClient.Builder(solrUrl).build();

        // 6. Create Solr collection with vector fields
        createBooksCollection();

        // 7. Load 1000 books
        loadBooks();
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
