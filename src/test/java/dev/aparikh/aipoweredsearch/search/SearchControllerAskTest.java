package dev.aparikh.aipoweredsearch.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aparikh.aipoweredsearch.search.model.AskRequest;
import dev.aparikh.aipoweredsearch.search.model.AskResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WebMvc test for the RAG ask endpoint.
 * Tests the controller layer in isolation with mocked service.
 */
@WebMvcTest(SearchController.class)
class SearchControllerAskTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SearchService searchService;

    @Test
    void shouldAskQuestionSuccessfully() throws Exception {
        // Given
        AskRequest request = new AskRequest(
                "What is Spring Boot?",
                "test-conversation-1"
        );

        AskResponse expectedResponse = new AskResponse(
                "Spring Boot is a framework that simplifies the development of Java applications. " +
                "It provides auto-configuration, embedded servers, and production-ready features.",
                "test-conversation-1",
                List.of("doc-1", "doc-2")
        );

        when(searchService.ask(any(AskRequest.class))).thenReturn(expectedResponse);

        // When & Then
        mockMvc.perform(post("/api/v1/search/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.answer").value(expectedResponse.answer()))
                .andExpect(jsonPath("$.conversationId").value("test-conversation-1"))
                .andExpect(jsonPath("$.sources").isArray())
                .andExpect(jsonPath("$.sources.length()").value(2));
    }

    @Test
    void shouldAskQuestionWithDefaultConversationId() throws Exception {
        // Given
        AskRequest request = new AskRequest("What are microservices?");

        AskResponse expectedResponse = new AskResponse(
                "Microservices are an architectural style that structures an application as a collection of small, " +
                "loosely coupled services.",
                "default",
                List.of()
        );

        when(searchService.ask(any(AskRequest.class))).thenReturn(expectedResponse);

        // When & Then
        mockMvc.perform(post("/api/v1/search/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.answer").exists())
                .andExpect(jsonPath("$.conversationId").value("default"))
                .andExpect(jsonPath("$.sources").isArray());
    }

    @Test
    void shouldHandleLongQuestion() throws Exception {
        // Given
        String longQuestion = "Can you explain in detail how Spring Boot's auto-configuration works, " +
                "including the role of @SpringBootApplication annotation, component scanning, " +
                "and how it differs from traditional Spring configuration?";

        AskRequest request = new AskRequest(longQuestion, "detailed-conv");

        AskResponse expectedResponse = new AskResponse(
                "Spring Boot's auto-configuration is a powerful feature that automatically configures your application " +
                "based on the dependencies present on the classpath...",
                "detailed-conv",
                List.of("doc-spring-boot-1", "doc-spring-boot-2", "doc-spring-boot-3")
        );

        when(searchService.ask(any(AskRequest.class))).thenReturn(expectedResponse);

        // When & Then
        mockMvc.perform(post("/api/v1/search/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").isNotEmpty())
                .andExpect(jsonPath("$.conversationId").value("detailed-conv"))
                .andExpect(jsonPath("$.sources.length()").value(3));
    }

    @Test
    void shouldHandleFollowUpQuestions() throws Exception {
        // Given - simulating a follow-up question in a conversation
        AskRequest followUpRequest = new AskRequest(
                "Can you explain more about that?",
                "conversation-123"
        );

        AskResponse expectedResponse = new AskResponse(
                "Certainly! Based on our previous discussion about Spring Boot, " +
                "the auto-configuration feature works by...",
                "conversation-123",
                List.of("doc-1")
        );

        when(searchService.ask(any(AskRequest.class))).thenReturn(expectedResponse);

        // When & Then
        mockMvc.perform(post("/api/v1/search/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(followUpRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").exists())
                .andExpect(jsonPath("$.conversationId").value("conversation-123"));
    }

    @Test
    void shouldHandleQuestionAboutNonExistentTopic() throws Exception {
        // Given
        AskRequest request = new AskRequest(
                "Tell me about quantum computing in underwater basket weaving",
                "random-conv"
        );

        AskResponse expectedResponse = new AskResponse(
                "I don't have any information in the indexed documents about that topic.",
                "random-conv",
                List.of()
        );

        when(searchService.ask(any(AskRequest.class))).thenReturn(expectedResponse);

        // When & Then
        mockMvc.perform(post("/api/v1/search/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").exists())
                .andExpect(jsonPath("$.sources").isEmpty());
    }

    @Test
    void shouldHandleEmptySources() throws Exception {
        // Given
        AskRequest request = new AskRequest("What is Docker?", "docker-conv");

        AskResponse expectedResponse = new AskResponse(
                "Docker is a platform for developing, shipping, and running applications in containers.",
                "docker-conv",
                List.of()
        );

        when(searchService.ask(any(AskRequest.class))).thenReturn(expectedResponse);

        // When & Then
        mockMvc.perform(post("/api/v1/search/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").exists())
                .andExpect(jsonPath("$.sources").isArray())
                .andExpect(jsonPath("$.sources").isEmpty());
    }

    @Test
    void shouldHandleSpecialCharactersInQuestion() throws Exception {
        // Given
        AskRequest request = new AskRequest(
                "What's the difference between @Component & @Service? Is it important?",
                "annotations-conv"
        );

        AskResponse expectedResponse = new AskResponse(
                "@Component and @Service are both Spring stereotypes, but @Service is more semantically specific...",
                "annotations-conv",
                List.of("doc-spring-annotations")
        );

        when(searchService.ask(any(AskRequest.class))).thenReturn(expectedResponse);

        // When & Then
        mockMvc.perform(post("/api/v1/search/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").exists());
    }

    @Test
    void shouldHandleMultipleSourceDocuments() throws Exception {
        // Given
        AskRequest request = new AskRequest(
                "Summarize everything about REST APIs",
                "rest-conv"
        );

        AskResponse expectedResponse = new AskResponse(
                "REST APIs are architectural styles for designing networked applications. Key concepts include...",
                "rest-conv",
                List.of("doc-rest-1", "doc-rest-2", "doc-rest-3", "doc-rest-4", "doc-rest-5")
        );

        when(searchService.ask(any(AskRequest.class))).thenReturn(expectedResponse);

        // When & Then
        mockMvc.perform(post("/api/v1/search/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").exists())
                .andExpect(jsonPath("$.sources.length()").value(5));
    }
}
