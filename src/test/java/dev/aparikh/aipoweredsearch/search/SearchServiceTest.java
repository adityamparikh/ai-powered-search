package dev.aparikh.aipoweredsearch.search;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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

    @Value("classpath:/prompts/system-message.st")
    Resource systemResource;

    @Value("classpath:/prompts/semantic-search-system-message.st")
    Resource semanticSystemResource;

    @BeforeEach
    void setUp() throws Exception {
        // Mock ChatClient's fluent API
        callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class, org.mockito.Mockito.RETURNS_SELF);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        // Create resources
        Resource systemRes = new ClassPathResource("prompts/system-message.st");
        Resource semanticRes = new ClassPathResource("prompts/semantic-search-system-message.st");

        searchService = new SearchService(systemRes, semanticRes, searchRepository, chatClient, ragChatClient, vectorStoreFactory);
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
}