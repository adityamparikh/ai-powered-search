package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.search.model.FieldInfo;
import dev.aparikh.aipoweredsearch.search.model.QueryGenerationResponse;
import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;
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
    private VectorStore vectorStore;

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
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(Resource.class))).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        // Create resources
        Resource systemRes = new ClassPathResource("prompts/system-message.st");
        Resource semanticRes = new ClassPathResource("prompts/semantic-search-system-message.st");

        searchService = new SearchService(systemRes, semanticRes, searchRepository, chatClient, ragChatClient, vectorStore);
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
}