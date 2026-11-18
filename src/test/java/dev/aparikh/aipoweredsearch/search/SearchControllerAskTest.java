package dev.aparikh.aipoweredsearch.search;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.aparikh.aipoweredsearch.search.model.AskRequest;
import dev.aparikh.aipoweredsearch.search.model.AskResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * WebMvc test for the RAG ask endpoint. Tests the controller layer in isolation with mocked
 * service.
 */
@WebMvcTest(SearchController.class)
class SearchControllerAskTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private SearchService searchService;

  /** A server-issued conversation ID, as {@link SearchService#ask} returns one. */
  private static final String CONVERSATION_ID = "3f2b8c1e-7a4d-4e9b-9c6a-1d2e3f4a5b6c";

  @Test
  void shouldAskQuestionSuccessfully() throws Exception {
    // Given
    AskRequest request = new AskRequest("What is Spring Boot?");

    AskResponse expectedResponse =
        new AskResponse(
            "Spring Boot is a framework that simplifies the development of Java applications. "
                + "It provides auto-configuration, embedded servers, and production-ready features.",
            CONVERSATION_ID,
            List.of("doc-1", "doc-2"));

    when(searchService.ask(any(AskRequest.class))).thenReturn(expectedResponse);

    // When & Then
    mockMvc
        .perform(
            post("/api/v1/search/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.answer").value(expectedResponse.answer()))
        .andExpect(jsonPath("$.conversationId").value(CONVERSATION_ID))
        .andExpect(jsonPath("$.sources").isArray())
        .andExpect(jsonPath("$.sources.length()").value(2));
  }

  @Test
  void shouldReturnTheServerIssuedConversationId() throws Exception {
    // Given
    AskRequest request = new AskRequest("What are microservices?");

    AskResponse expectedResponse =
        new AskResponse(
            "Microservices are an architectural style that structures an application as a collection of small, "
                + "loosely coupled services.",
            CONVERSATION_ID,
            List.of());

    when(searchService.ask(any(AskRequest.class))).thenReturn(expectedResponse);

    // When & Then
    mockMvc
        .perform(
            post("/api/v1/search/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.answer").exists())
        .andExpect(jsonPath("$.conversationId").value(CONVERSATION_ID))
        .andExpect(jsonPath("$.sources").isArray());
  }

  @Test
  void shouldHandleLongQuestion() throws Exception {
    // Given
    String longQuestion =
        "Can you explain in detail how Spring Boot's auto-configuration works, "
            + "including the role of @SpringBootApplication annotation, component scanning, "
            + "and how it differs from traditional Spring configuration?";

    AskRequest request = new AskRequest(longQuestion);

    AskResponse expectedResponse =
        new AskResponse(
            "Spring Boot's auto-configuration is a powerful feature that automatically configures your application "
                + "based on the dependencies present on the classpath...",
            CONVERSATION_ID,
            List.of("doc-spring-boot-1", "doc-spring-boot-2", "doc-spring-boot-3"));

    when(searchService.ask(any(AskRequest.class))).thenReturn(expectedResponse);

    // When & Then
    mockMvc
        .perform(
            post("/api/v1/search/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").isNotEmpty())
        .andExpect(jsonPath("$.conversationId").value(CONVERSATION_ID))
        .andExpect(jsonPath("$.sources.length()").value(3));
  }

  @Test
  void shouldHandleFollowUpQuestions() throws Exception {
    // Given - simulating a follow-up question in a conversation
    AskRequest followUpRequest =
        new AskRequest("Can you explain more about that?", CONVERSATION_ID);

    AskResponse expectedResponse =
        new AskResponse(
            "Certainly! Based on our previous discussion about Spring Boot, "
                + "the auto-configuration feature works by...",
            CONVERSATION_ID,
            List.of("doc-1"));

    when(searchService.ask(any(AskRequest.class))).thenReturn(expectedResponse);

    // When & Then
    mockMvc
        .perform(
            post("/api/v1/search/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(followUpRequest)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").exists())
        .andExpect(jsonPath("$.conversationId").value(CONVERSATION_ID));
  }

  @Test
  void shouldHandleQuestionAboutNonExistentTopic() throws Exception {
    // Given
    AskRequest request =
        new AskRequest("Tell me about quantum computing in underwater basket weaving");

    AskResponse expectedResponse =
        new AskResponse(
            "I don't have any information in the indexed documents about that topic.",
            CONVERSATION_ID,
            List.of());

    when(searchService.ask(any(AskRequest.class))).thenReturn(expectedResponse);

    // When & Then
    mockMvc
        .perform(
            post("/api/v1/search/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").exists())
        .andExpect(jsonPath("$.sources").isEmpty());
  }

  @Test
  void shouldHandleEmptySources() throws Exception {
    // Given
    AskRequest request = new AskRequest("What is Docker?");

    AskResponse expectedResponse =
        new AskResponse(
            "Docker is a platform for developing, shipping, and running applications in containers.",
            CONVERSATION_ID,
            List.of());

    when(searchService.ask(any(AskRequest.class))).thenReturn(expectedResponse);

    // When & Then
    mockMvc
        .perform(
            post("/api/v1/search/ask")
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
    AskRequest request =
        new AskRequest("What's the difference between @Component & @Service? Is it important?");

    AskResponse expectedResponse =
        new AskResponse(
            "@Component and @Service are both Spring stereotypes, but @Service is more semantically specific...",
            CONVERSATION_ID,
            List.of("doc-spring-annotations"));

    when(searchService.ask(any(AskRequest.class))).thenReturn(expectedResponse);

    // When & Then
    mockMvc
        .perform(
            post("/api/v1/search/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").exists());
  }

  @Test
  void shouldHandleMultipleSourceDocuments() throws Exception {
    // Given
    AskRequest request = new AskRequest("Summarize everything about REST APIs");

    AskResponse expectedResponse =
        new AskResponse(
            "REST APIs are architectural styles for designing networked applications. Key concepts include...",
            CONVERSATION_ID,
            List.of("doc-rest-1", "doc-rest-2", "doc-rest-3", "doc-rest-4", "doc-rest-5"));

    when(searchService.ask(any(AskRequest.class))).thenReturn(expectedResponse);

    // When & Then
    mockMvc
        .perform(
            post("/api/v1/search/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").exists())
        .andExpect(jsonPath("$.sources.length()").value(5));
  }

  @Test
  void shouldReturn404ProblemDetailForAConversationIdTheServerDidNotIssue() throws Exception {
    when(searchService.ask(any(AskRequest.class))).thenThrow(new ConversationNotFoundException());

    mockMvc
        .perform(
            post("/api/v1/search/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(new AskRequest("Tell me more", "made-up-id"))))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.title").value("Conversation not found"))
        .andExpect(jsonPath("$.detail").exists())
        .andExpect(jsonPath("$.instance").value("/api/v1/search/ask"));
  }
}
