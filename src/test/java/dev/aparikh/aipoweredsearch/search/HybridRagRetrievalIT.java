package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import dev.aparikh.aipoweredsearch.embedding.EmbeddingService;
import dev.aparikh.aipoweredsearch.search.model.AskRequest;
import dev.aparikh.aipoweredsearch.search.model.AskResponse;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solr.SolrContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration test proving the RAG pipeline retrieves context with hybrid search rather than
 * vector similarity alone.
 *
 * <p>The scenario is the one dense retrieval is known to fail: a document whose relevance is purely
 * lexical. {@code doc-lexical} is the only document containing the error code {@code ZX4711}, but
 * its embedding is deliberately orthogonal to the question's, so a KNN search ranks it last. A
 * vector-only retriever with a small topK therefore never sees it. Hybrid retrieval finds it
 * through the BM25 leg and RRF promotes it to the top.</p>
 *
 * <p>Embeddings and the chat model are mocked, so this test needs Docker but no API keys.</p>
 */
@SpringBootTest
@Testcontainers
@Import({PostgresTestConfiguration.class, SolrTestConfiguration.class})
class HybridRagRetrievalIT {

    private static final String COLLECTION = "hybrid-rag-it";

    /** The question's embedding. Documents are placed near or far from this on purpose. */
    private static final String QUERY_VECTOR = "[1.0, 0.0, 0.0, 0.0, 0.0]";

    @Autowired
    private SolrContainer solrContainer;

    @Autowired
    private SolrClient solrClient;

    @Autowired
    private DocumentRetriever documentRetriever;

    @Autowired
    private SearchRepository searchRepository;

    @Autowired
    private SearchService searchService;

    @MockitoBean
    private EmbeddingService embeddingService;

    @MockitoBean
    private ChatModel chatModel;

    private final AtomicReference<String> capturedPrompt = new AtomicReference<>();

    @DynamicPropertySource
    static void ragProperties(DynamicPropertyRegistry registry) {
        registry.add("solr.default.collection", () -> COLLECTION);
        // Keep topK small: the whole point is that the lexical document does not fit in the
        // vector-only top slots.
        registry.add("rag.retrieval.top-k", () -> 2);
        registry.add("rag.retrieval.min-score", () -> 0.0);
        registry.add("spring.ai.openai.api-key", () -> "test-key");
    }

    @BeforeEach
    void setUp() throws Exception {
        createCollectionWithVectorField();
        indexFixtures();

        when(embeddingService.embedAndFormatForSolr(anyString())).thenReturn(QUERY_VECTOR);

        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            capturedPrompt.set(prompt.getInstructions().stream()
                    .map(Message::getText)
                    .collect(Collectors.joining("\n")));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("Reset the unit."))));
        });
    }

    @Test
    void ragRetrieverShouldBeHybrid() {
        assertThat(documentRetriever)
                .as("RAG context must come from hybrid retrieval, not vector-only retrieval")
                .isInstanceOf(HybridDocumentRetriever.class);
    }

    @Test
    void shouldRetrieveLexicallyRelevantDocumentThatVectorSearchRanksLast() throws Exception {
        // Given: a question whose only truly relevant document is a poor embedding match
        Query question = new Query("ZX4711 troubleshooting steps");

        // When
        List<Document> hybridContext = documentRetriever.retrieve(question);
        List<Map<String, Object>> vectorOnly = searchRepository.executeVectorSearch(
                COLLECTION, question.text(), 2, null, null, null);

        // Then: vector-only retrieval fills its slots with the semantically-near documents
        assertThat(ids(vectorOnly))
                .as("dense retrieval should miss the lexical match at this topK")
                .doesNotContain("doc-lexical");

        // ...while hybrid retrieval surfaces the document that actually answers the question
        assertThat(hybridContext).isNotEmpty();
        assertThat(hybridContext.get(0).getId())
                .as("RRF should promote the document matched by both legs' evidence")
                .isEqualTo("doc-lexical");
        assertThat(hybridContext.get(0).getText()).contains("ZX4711");
    }

    @Test
    void shouldRecordWhichLegSurfacedEachDocument() {
        List<Document> context = documentRetriever.retrieve(new Query("ZX4711 troubleshooting steps"));

        Map<String, Object> metadata = context.get(0).getMetadata();
        assertThat(metadata).containsKey("rrf_score");
        assertThat(metadata)
                .as("the lexical document should be credited to the keyword leg")
                .containsEntry("keyword_rank", 1);
    }

    @Test
    void shouldNotLeakRawEmbeddingsIntoRagContext() {
        List<Document> context = documentRetriever.retrieve(new Query("ZX4711 troubleshooting steps"));

        assertThat(context).isNotEmpty();
        assertThat(context).allSatisfy(document ->
                assertThat(document.getMetadata()).doesNotContainKey("vector"));
    }

    @Test
    void askShouldGroundAnswerInHybridContextAndReportSources() {
        // When
        AskResponse response = searchService.ask(
                new AskRequest("What are the ZX4711 troubleshooting steps?", "hybrid-rag-it-conversation"));

        // Then: the answer came back and the lexical document reached the model as context
        assertThat(response.answer()).isEqualTo("Reset the unit.");
        assertThat(capturedPrompt.get())
                .as("hybrid-retrieved context must be injected into the prompt")
                .contains("ZX4711");

        // ...and the API reports what the answer was grounded in
        assertThat(response.sources()).contains("doc-lexical");
        assertThat(response.conversationId()).isEqualTo("hybrid-rag-it-conversation");
    }

    // ==================== Fixtures ====================

    private List<String> ids(List<Map<String, Object>> documents) {
        return documents.stream().map(d -> String.valueOf(d.get("id"))).toList();
    }

    private void createCollectionWithVectorField() throws Exception {
        solrContainer.execInContainer("/opt/solr/bin/solr", "create_collection",
                "-c", COLLECTION, "-d", "_default", "-shards", "1", "-replicationFactor", "1");

        // Poll rather than sleeping a fixed interval: collection creation and schema propagation
        // take an unpredictable amount of time, and Awaitility is the project's convention here.
        await().atMost(Duration.ofSeconds(60)).ignoreExceptions().untilAsserted(() ->
                assertThat(solrClient.query(COLLECTION, new SolrQuery("*:*").setRows(0))).isNotNull());

        solrContainer.execInContainer("curl", "-X", "POST",
                "-H", "Content-type:application/json",
                "--data-binary",
                """
                        {"add-field-type":{"name":"knn_vector","class":"solr.DenseVectorField",\
                        "vectorDimension":5,"similarityFunction":"cosine"}}
                        """.strip(),
                "http://localhost:8983/solr/" + COLLECTION + "/schema");

        solrContainer.execInContainer("curl", "-X", "POST",
                "-H", "Content-type:application/json",
                "--data-binary",
                """
                        {"add-field":{"name":"vector","type":"knn_vector","indexed":true,"stored":true}}
                        """.strip(),
                "http://localhost:8983/solr/" + COLLECTION + "/schema");

        // Wait for the vector field to appear in the schema rather than guessing at a delay.
        await().atMost(Duration.ofSeconds(60)).ignoreExceptions().untilAsserted(() ->
                assertThat(new SchemaRequest.Field("vector").process(solrClient, COLLECTION).getField())
                        .isNotNull());
    }

    /**
     * Indexes four documents. Only doc-lexical mentions ZX4711, and its embedding is orthogonal to
     * the query vector, so KNN ranks it last of the four.
     */
    private void indexFixtures() throws Exception {
        solrClient.deleteByQuery(COLLECTION, "*:*");

        solrClient.add(COLLECTION, List.of(
                document("doc-lexical",
                        "Error ZX4711 indicates a stalled coolant pump. Power cycle the unit, then clear the fault.",
                        List.of(0.0f, 1.0f, 0.0f, 0.0f, 0.0f)),
                document("doc-semantic",
                        "General maintenance advice for keeping industrial equipment running smoothly.",
                        List.of(1.0f, 0.0f, 0.0f, 0.0f, 0.0f)),
                document("doc-filler-1",
                        "Routine inspection checklists for factory floor machinery.",
                        List.of(0.9f, 0.1f, 0.0f, 0.0f, 0.0f)),
                document("doc-filler-2",
                        "Scheduling preventive maintenance windows across production lines.",
                        List.of(0.8f, 0.2f, 0.0f, 0.0f, 0.0f))));

        solrClient.commit(COLLECTION);
    }

    private SolrInputDocument document(String id, String content, List<Float> vector) {
        SolrInputDocument document = new SolrInputDocument();
        document.addField("id", id);
        document.addField("content", content);
        document.addField("vector", vector);
        return document;
    }
}
