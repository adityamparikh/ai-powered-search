package dev.aparikh.aipoweredsearch.search.service;

import dev.aparikh.aipoweredsearch.search.model.QueryGenerationResponse;
import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import dev.aparikh.aipoweredsearch.search.repository.SearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock
    private SearchRepository searchRepository;

    @Mock
    private ChatModel chatModel;

    private SearchService searchService;

    @BeforeEach
    void setUp() {
        // Mock ChatModel to return JSON response
        String jsonResponse = """
                {
                    "q": "*:*",
                    "fq": ["name:Spring"],
                    "sort": "id asc",
                    "facet.fields": ["name"],
                    "facet.query": "description:boot"
                }
                """;

        AssistantMessage assistantMessage = new AssistantMessage(jsonResponse);
        Generation generation = new Generation(assistantMessage);
        ChatResponse mockResponse = new ChatResponse(List.of(generation));
        when(chatModel.call(any(Prompt.class))).thenReturn(mockResponse);
        
        searchService = new SearchService(searchRepository, chatModel);
    }

    @Test
    void shouldSearchWithAiGeneratedQuery() {
        // Given
        String collection = "test-collection";
        String freeTextQuery = "find documents about spring boot";
        List<String> fields = List.of("id", "name", "description");
        
        // Mock repository getFields
        when(searchRepository.getFields(collection)).thenReturn(fields);
        
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
        assertEquals(1, response.getDocuments().size());
        assertEquals("1", response.getDocuments().get(0).get("id"));
        assertEquals("Test Document", response.getDocuments().get(0).get("name"));
    }
}