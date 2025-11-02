package dev.aparikh.aipoweredsearch.search.service;

import dev.aparikh.aipoweredsearch.search.model.FieldInfo;
import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import dev.aparikh.aipoweredsearch.search.repository.SearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.lang.reflect.Field;
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
    @Mock
    private ChatMemory chatMemory;

    private SearchService searchService;

    @Value("classpath:/prompts/system-message.st")
    Resource systemResource;

    @BeforeEach
    void setUp() throws Exception {
        // Mock ChatModel to return JSON response
        String jsonResponse = """
                {
                    "q": "*:*",
                    "fq": ["name:Spring"],
                    "sort": "id asc",
                    "fl": "id,name,description",
                    "facet.fields": ["name"],
                    "facet.query": "description:boot"
                }
                """;

        AssistantMessage assistantMessage = new AssistantMessage(jsonResponse);
        Generation generation = new Generation(assistantMessage);
        ChatResponse mockResponse = new ChatResponse(List.of(generation));
        when(chatModel.call(any(Prompt.class))).thenReturn(mockResponse);

        searchService = new SearchService(systemResource, searchRepository, chatModel, chatMemory);

        // Use reflection to set the systemResource field
        Resource resource = new ClassPathResource("prompts/system-message.st");
        Field resourceField = SearchService.class.getDeclaredField("systemResource");
        resourceField.setAccessible(true);
        resourceField.set(searchService, resource);
    }

    @Test
    void shouldSearchWithAiGeneratedQuery() {
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