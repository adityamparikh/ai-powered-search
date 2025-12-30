package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.search.model.AskRequest;
import dev.aparikh.aipoweredsearch.search.model.AskResponse;
import dev.aparikh.aipoweredsearch.search.model.FieldInfo;
import dev.aparikh.aipoweredsearch.search.model.QueryGenerationResponse;
import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import dev.aparikh.aipoweredsearch.solr.vectorstore.VectorStoreFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock
    private SearchRepository searchRepository;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient ragChatClient;

    @Mock
    private VectorStoreFactory vectorStoreFactory;

    private SearchService searchService;

    private ChatClient.CallResponseSpec callResponseSpec;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec ragCallResponseSpec;
    private ChatClient.ChatClientRequestSpec ragRequestSpec;

    @Mock
    private VectorStore vectorStore;

    @BeforeEach
    void setUp() throws Exception {
        // Mock ChatClient's fluent API (lenient to avoid unnecessary stubbing exceptions)
        callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class, org.mockito.Mockito.RETURNS_SELF);

        lenient().when(chatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(callResponseSpec);

        // Mock RAG ChatClient's fluent API (lenient to avoid unnecessary stubbing exceptions)
        ragCallResponseSpec = mock(ChatClient.CallResponseSpec.class);
        ragRequestSpec = mock(ChatClient.ChatClientRequestSpec.class, org.mockito.Mockito.RETURNS_SELF);

        lenient().when(ragChatClient.prompt()).thenReturn(ragRequestSpec);
        lenient().when(ragRequestSpec.call()).thenReturn(ragCallResponseSpec);

        // Create resources
        Resource systemRes = new ClassPathResource("prompts/system-message.st");

        searchService = new SearchService(systemRes, searchRepository, chatClient, ragChatClient, vectorStoreFactory);
    }

    @Test
    void shouldSearchWithAiGeneratedQuery() throws Exception {
        // Given
        String collection = "test-collection";
        String freeTextQuery = "find documents about spring boot";
        List<FieldInfo> fields = List.of(
                new FieldInfo("id", "string", false, true, false, true),
                new FieldInfo("name", "text_general", false, true, false, true),
                new FieldInfo("description", "text_general", false, true, false, true)
        );

        // Mock repository getFieldsWithSchema
        when(searchRepository.getFieldsWithSchema(collection)).thenReturn(fields);

        // Mock QueryGenerationResponse from ChatClient
        QueryGenerationResponse queryGenResponse = new QueryGenerationResponse(
                "*:*",
                List.of("name:Spring"),
                "id asc",
                "id,name,description",
                List.of("name"),
                "description:boot"
        );
        when(callResponseSpec.entity(QueryGenerationResponse.class)).thenReturn(queryGenResponse);

        // Mock repository search
        SearchResponse expectedResponse = new SearchResponse(
                Collections.singletonList(Map.of("id", "1", "name", "Test Document")),
                Collections.emptyMap()
        );
        when(searchRepository.search(anyString(), any(SearchRequest.class))).thenReturn(expectedResponse);

        // When
        SearchResponse response = searchService.search(collection, freeTextQuery);

        // Then
        assertNotNull(response);
        assertEquals(1, response.documents().size());
        assertEquals("1", response.documents().getFirst().get("id"));
        assertEquals("Test Document", response.documents().getFirst().get("name"));
    }

    @Test
    void shouldPerformHybridSearchWithDefaultParameters() throws Exception {
        // Given
        String collection = "test-collection";
        String freeTextQuery = "machine learning frameworks";
        List<FieldInfo> fields = List.of(
                new FieldInfo("id", "string", false, true, false, true),
                new FieldInfo("content", "text_general", false, true, false, true),
                new FieldInfo("category", "string", false, true, false, true)
        );

        // Mock repository getFieldsWithSchema
        when(searchRepository.getFieldsWithSchema(collection)).thenReturn(fields);

        // Mock QueryGenerationResponse from ChatClient
        QueryGenerationResponse queryGenResponse = new QueryGenerationResponse(
                "machine learning frameworks",
                List.of(),
                null,
                null,
                List.of(),
                null
        );
        when(callResponseSpec.entity(QueryGenerationResponse.class)).thenReturn(queryGenResponse);

        // Mock repository hybridSearch with default topK=100
        SearchResponse expectedResponse = new SearchResponse(
                List.of(
                        Map.of("id", "1", "content", "TensorFlow is a popular ML framework", "score", 0.95),
                        Map.of("id", "2", "content", "PyTorch for deep learning", "score", 0.88)
                ),
                Collections.emptyMap()
        );
        when(searchRepository.executeHybridRerankSearch(
                anyString(),
                anyString(),
                any(Integer.class),
                any(),
                any(),
                any()
        )).thenReturn(expectedResponse);

        // When
        SearchResponse response = searchService.hybridSearch(collection, freeTextQuery, null, null, null);

        // Then
        assertNotNull(response);
        assertEquals(2, response.documents().size());
        assertEquals("1", response.documents().get(0).get("id"));
        assertEquals(0.95, response.documents().get(0).get("score"));
        assertEquals("2", response.documents().get(1).get("id"));
        assertEquals(0.88, response.documents().get(1).get("score"));
    }

    @Test
    void shouldPerformHybridSearchWithCustomTopK() throws Exception {
        // Given
        String collection = "test-collection";
        String freeTextQuery = "java spring boot tutorials";
        Integer topK = 50;
        List<FieldInfo> fields = List.of(
                new FieldInfo("id", "string", false, true, false, true),
                new FieldInfo("content", "text_general", false, true, false, true)
        );

        when(searchRepository.getFieldsWithSchema(collection)).thenReturn(fields);

        QueryGenerationResponse queryGenResponse = new QueryGenerationResponse(
                "java spring boot tutorials",
                List.of(),
                null,
                null,
                List.of(),
                null
        );
        when(callResponseSpec.entity(QueryGenerationResponse.class)).thenReturn(queryGenResponse);

        SearchResponse expectedResponse = new SearchResponse(
                List.of(Map.of("id", "1", "content", "Spring Boot Tutorial", "score", 0.92)),
                Collections.emptyMap()
        );
        when(searchRepository.executeHybridRerankSearch(
                collection,
                "java spring boot tutorials",
                topK,
                null,
                null,
                null
        )).thenReturn(expectedResponse);

        // When
        SearchResponse response = searchService.hybridSearch(collection, freeTextQuery, topK, null, null);

        // Then
        assertNotNull(response);
        assertEquals(1, response.documents().size());
        assertEquals("1", response.documents().get(0).get("id"));
    }

    @Test
    void shouldPerformHybridSearchWithMinScoreFilter() throws Exception {
        // Given
        String collection = "test-collection";
        String freeTextQuery = "natural language processing";
        Integer topK = 20;
        Double minScore = 0.75;
        List<FieldInfo> fields = List.of(
                new FieldInfo("id", "string", false, true, false, true),
                new FieldInfo("content", "text_general", false, true, false, true)
        );

        when(searchRepository.getFieldsWithSchema(collection)).thenReturn(fields);

        QueryGenerationResponse queryGenResponse = new QueryGenerationResponse(
                "natural language processing",
                List.of(),
                null,
                null,
                List.of(),
                null
        );
        when(callResponseSpec.entity(QueryGenerationResponse.class)).thenReturn(queryGenResponse);

        // Mock hybridSearch - minScore filtering happens in repository
        SearchResponse expectedResponse = new SearchResponse(
                List.of(
                        Map.of("id", "1", "content", "NLP with transformers", "score", 0.89),
                        Map.of("id", "2", "content", "Deep learning for NLP", "score", 0.76)
                        // Documents with score < 0.75 are filtered out by repository
                ),
                Collections.emptyMap()
        );
        when(searchRepository.executeHybridRerankSearch(
                collection,
                "natural language processing",
                topK,
                null,
                null,
                minScore
        )).thenReturn(expectedResponse);

        // When
        SearchResponse response = searchService.hybridSearch(collection, freeTextQuery, topK, minScore, null);

        // Then
        assertNotNull(response);
        assertEquals(2, response.documents().size());
        // Verify all results meet minimum score threshold
        response.documents().forEach(doc -> {
            Double score = (Double) doc.get("score");
            assert score >= minScore : "Score " + score + " should be >= " + minScore;
        });
    }

    @Test
    void shouldPerformHybridSearchWithFieldSelection() throws Exception {
        // Given
        String collection = "test-collection";
        String freeTextQuery = "cloud computing platforms";
        String fieldsCsv = "id,title,author";
        List<FieldInfo> fields = List.of(
                new FieldInfo("id", "string", false, true, false, true),
                new FieldInfo("title", "text_general", false, true, false, true),
                new FieldInfo("author", "string", false, true, false, true),
                new FieldInfo("content", "text_general", false, true, false, true)
        );

        when(searchRepository.getFieldsWithSchema(collection)).thenReturn(fields);

        QueryGenerationResponse queryGenResponse = new QueryGenerationResponse(
                "cloud computing platforms",
                List.of(),
                null,
                null,
                List.of(),
                null
        );
        when(callResponseSpec.entity(QueryGenerationResponse.class)).thenReturn(queryGenResponse);

        SearchResponse expectedResponse = new SearchResponse(
                List.of(
                        Map.of("id", "1", "title", "AWS Guide", "author", "John Doe", "score", 0.91)
                ),
                Collections.emptyMap()
        );
        when(searchRepository.executeHybridRerankSearch(
                collection,
                "cloud computing platforms",
                100,
                null,
                fieldsCsv,
                null
        )).thenReturn(expectedResponse);

        // When
        SearchResponse response = searchService.hybridSearch(collection, freeTextQuery, null, null, fieldsCsv);

        // Then
        assertNotNull(response);
        assertEquals(1, response.documents().size());
        Map<String, Object> doc = response.documents().get(0);
        assertEquals("1", doc.get("id"));
        assertEquals("AWS Guide", doc.get("title"));
        assertEquals("John Doe", doc.get("author"));
        // Content field should not be present (unless score is always included)
    }

    @Test
    void shouldPerformHybridSearchWithFilters() throws Exception {
        // Given
        String collection = "test-collection";
        String freeTextQuery = "python tutorials published after 2023";
        List<FieldInfo> fields = List.of(
                new FieldInfo("id", "string", false, true, false, true),
                new FieldInfo("content", "text_general", false, true, false, true),
                new FieldInfo("year", "pint", false, true, false, true),
                new FieldInfo("language", "string", false, true, false, true)
        );

        when(searchRepository.getFieldsWithSchema(collection)).thenReturn(fields);

        // AI should parse filters from the query
        QueryGenerationResponse queryGenResponse = new QueryGenerationResponse(
                "python tutorials",
                List.of("year:[2023 TO *]", "language:python"),
                null,
                null,
                List.of(),
                null
        );
        when(callResponseSpec.entity(QueryGenerationResponse.class)).thenReturn(queryGenResponse);

        SearchResponse expectedResponse = new SearchResponse(
                List.of(
                        Map.of("id", "1", "content", "Python 3.12 Tutorial", "year", 2024, "score", 0.93)
                ),
                Collections.emptyMap()
        );
        when(searchRepository.executeHybridRerankSearch(
                collection,
                "python tutorials",
                100,
                "year:[2023 TO *] AND language:python",
                null,
                null
        )).thenReturn(expectedResponse);

        // When
        SearchResponse response = searchService.hybridSearch(collection, freeTextQuery, null, null, null);

        // Then
        assertNotNull(response);
        assertEquals(1, response.documents().size());
        Map<String, Object> doc = response.documents().get(0);
        assertEquals("1", doc.get("id"));
        assertEquals(2024, doc.get("year"));
    }

    @Test
    void shouldHandleEmptyHybridSearchResults() throws Exception {
        // Given
        String collection = "test-collection";
        String freeTextQuery = "nonexistent topic xyz123";
        List<FieldInfo> fields = List.of(
                new FieldInfo("id", "string", false, true, false, true),
                new FieldInfo("content", "text_general", false, true, false, true)
        );

        when(searchRepository.getFieldsWithSchema(collection)).thenReturn(fields);

        QueryGenerationResponse queryGenResponse = new QueryGenerationResponse(
                "nonexistent topic xyz123",
                List.of(),
                null,
                null,
                List.of(),
                null
        );
        when(callResponseSpec.entity(QueryGenerationResponse.class)).thenReturn(queryGenResponse);

        SearchResponse expectedResponse = new SearchResponse(
                Collections.emptyList(),
                Collections.emptyMap()
        );
        when(searchRepository.executeHybridRerankSearch(
                anyString(),
                anyString(),
                any(Integer.class),
                any(),
                any(),
                any()
        )).thenReturn(expectedResponse);

        // When
        SearchResponse response = searchService.hybridSearch(collection, freeTextQuery, null, null, null);

        // Then
        assertNotNull(response);
        assertEquals(0, response.documents().size());
    }

    @Test
    void shouldPerformRagQuestionAnsweringWithDefaultConversationId() {
        // Given
        String question = "What are the benefits of Spring Boot?";
        String expectedAnswer = "Spring Boot provides several benefits including auto-configuration, " +
                "embedded servers, production-ready features, and simplified dependency management. " +
                "It reduces boilerplate code and allows rapid application development.";

        AskRequest request = new AskRequest(question);

        // Mock RAG ChatClient to return answer
        when(ragCallResponseSpec.content()).thenReturn(expectedAnswer);

        // When
        AskResponse response = searchService.ask(request);

        // Then
        assertNotNull(response);
        assertEquals(expectedAnswer, response.answer());
        assertEquals("default", response.conversationId());
        assertNotNull(response.sources());
        assertEquals(0, response.sources().size()); // Sources not tracked in current implementation
    }

    @Test
    void shouldPerformRagQuestionAnsweringWithCustomConversationId() {
        // Given
        String question = "How does Spring Security work?";
        String conversationId = "user-session-123";
        String expectedAnswer = "Spring Security is a powerful authentication and authorization framework. " +
                "It works by intercepting requests through a chain of filters, validating credentials, " +
                "and managing security contexts. It supports various authentication mechanisms including " +
                "form-based login, OAuth2, JWT, and more.";

        AskRequest request = new AskRequest(question, conversationId);

        // Mock RAG ChatClient to return answer
        when(ragCallResponseSpec.content()).thenReturn(expectedAnswer);

        // When
        AskResponse response = searchService.ask(request);

        // Then
        assertNotNull(response);
        assertEquals(expectedAnswer, response.answer());
        assertEquals(conversationId, response.conversationId());
        assertNotNull(response.sources());
        assertEquals(0, response.sources().size());
    }

    @Test
    void shouldHandleRagRequestWithNullConversationId() {
        // Given
        String question = "What is dependency injection?";
        String expectedAnswer = "Dependency injection is a design pattern where objects receive their dependencies " +
                "from external sources rather than creating them internally. Spring Framework implements DI " +
                "through constructor injection, setter injection, and field injection.";

        AskRequest request = new AskRequest(question, null);

        // Mock RAG ChatClient to return answer
        when(ragCallResponseSpec.content()).thenReturn(expectedAnswer);

        // When
        AskResponse response = searchService.ask(request);

        // Then
        assertNotNull(response);
        assertEquals(expectedAnswer, response.answer());
        assertEquals("default", response.conversationId()); // Should default to "default"
        assertNotNull(response.sources());
    }

    // ============== Semantic Search Tests ==============

    @Test
    void shouldPerformSemanticSearchUsingVectorStore() {
        // Given
        String collection = "test-collection";
        String query = "machine learning tutorials";

        // Mock VectorStoreFactory to return our mock VectorStore
        when(vectorStoreFactory.forCollection(collection)).thenReturn(vectorStore);

        // Create mock Spring AI Documents
        Document doc1 = Document.builder()
                .id("1")
                .text("Introduction to Machine Learning")
                .metadata(Map.of("category", "AI", "score", 0.95))
                .build();
        Document doc2 = Document.builder()
                .id("2")
                .text("Deep Learning Fundamentals")
                .metadata(Map.of("category", "AI", "score", 0.88))
                .build();

        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(List.of(doc1, doc2));

        // When
        SearchResponse response = searchService.semanticSearch(collection, query, null, null, null);

        // Then
        assertNotNull(response);
        assertEquals(2, response.documents().size());

        // Verify first document
        Map<String, Object> firstDoc = response.documents().get(0);
        assertEquals("1", firstDoc.get("id"));
        assertEquals("Introduction to Machine Learning", firstDoc.get("content"));
        assertEquals("AI", firstDoc.get("category"));
        assertEquals(0.95, firstDoc.get("score"));

        // Verify second document
        Map<String, Object> secondDoc = response.documents().get(1);
        assertEquals("2", secondDoc.get("id"));
        assertEquals("Deep Learning Fundamentals", secondDoc.get("content"));

        // Verify VectorStore was called
        verify(vectorStoreFactory).forCollection(collection);
        verify(vectorStore).similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class));
    }

    @Test
    void shouldPerformSemanticSearchWithCustomTopK() {
        // Given
        String collection = "test-collection";
        String query = "spring boot";
        Integer topK = 5;

        when(vectorStoreFactory.forCollection(collection)).thenReturn(vectorStore);

        Document doc = Document.builder()
                .id("1")
                .text("Spring Boot Guide")
                .metadata(Map.of("score", 0.92))
                .build();

        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(List.of(doc));

        // When
        SearchResponse response = searchService.semanticSearch(collection, query, topK, null, null);

        // Then
        assertNotNull(response);
        assertEquals(1, response.documents().size());
        verify(vectorStore).similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class));
    }

    @Test
    void shouldPerformSemanticSearchWithMinScoreThreshold() {
        // Given
        String collection = "test-collection";
        String query = "kubernetes";
        Double minScore = 0.8;

        when(vectorStoreFactory.forCollection(collection)).thenReturn(vectorStore);

        // VectorStore should apply the threshold and return only high-scoring docs
        Document doc = Document.builder()
                .id("1")
                .text("Kubernetes in Production")
                .metadata(Map.of("score", 0.85))
                .build();

        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(List.of(doc));

        // When
        SearchResponse response = searchService.semanticSearch(collection, query, null, minScore, null);

        // Then
        assertNotNull(response);
        assertEquals(1, response.documents().size());
        assertEquals(0.85, response.documents().get(0).get("score"));
    }

    @Test
    void shouldPerformSemanticSearchWithFieldSelection() {
        // Given
        String collection = "test-collection";
        String query = "docker containers";
        String fieldsCsv = "id,title";

        when(vectorStoreFactory.forCollection(collection)).thenReturn(vectorStore);

        Document doc = Document.builder()
                .id("1")
                .text("Docker Containers 101")
                .metadata(Map.of("title", "Docker Guide", "author", "Jane Doe", "score", 0.90))
                .build();

        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(List.of(doc));

        // When
        SearchResponse response = searchService.semanticSearch(collection, query, null, null, fieldsCsv);

        // Then
        assertNotNull(response);
        assertEquals(1, response.documents().size());
        Map<String, Object> docMap = response.documents().get(0);
        assertTrue(docMap.containsKey("id"));
        assertTrue(docMap.containsKey("title"));
        // author should be excluded since it's not in fieldsCsv
        // Note: id is always included regardless of fieldsCsv
    }

    @Test
    void shouldHandleEmptySemanticSearchResults() {
        // Given
        String collection = "test-collection";
        String query = "nonexistent topic xyz";

        when(vectorStoreFactory.forCollection(collection)).thenReturn(vectorStore);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(Collections.emptyList());

        // When
        SearchResponse response = searchService.semanticSearch(collection, query, null, null, null);

        // Then
        assertNotNull(response);
        assertEquals(0, response.documents().size());
    }

    @Test
    void shouldStripMetadataPrefixInSemanticSearchResults() {
        // Given
        String collection = "test-collection";
        String query = "test query";

        when(vectorStoreFactory.forCollection(collection)).thenReturn(vectorStore);

        // Document with metadata_ prefixed fields (as returned by SolrVectorStore)
        Document doc = Document.builder()
                .id("1")
                .text("Test content")
                .metadata(Map.of(
                        "metadata_category", "tech",
                        "metadata_year", 2024L,  // Long type from Solr
                        "score", 0.88
                ))
                .build();

        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(List.of(doc));

        // When
        SearchResponse response = searchService.semanticSearch(collection, query, null, null, null);

        // Then
        assertNotNull(response);
        assertEquals(1, response.documents().size());
        Map<String, Object> docMap = response.documents().get(0);

        // Verify metadata_ prefix is stripped
        assertTrue(docMap.containsKey("category"));
        assertEquals("tech", docMap.get("category"));

        // Verify year is converted from Long to Integer
        assertTrue(docMap.containsKey("year"));
        assertEquals(2024, docMap.get("year"));
    }
}