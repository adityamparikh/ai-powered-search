package dev.aparikh.aipoweredsearch.search;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.aparikh.aipoweredsearch.search.model.AskRequest;
import dev.aparikh.aipoweredsearch.search.model.AskResponse;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Input limits on the search endpoints, at their defaults: a request at a limit is served, one past
 * it gets a 400 problem-details body naming the limit, and the service is never called.
 */
@WebMvcTest(SearchController.class)
class SearchControllerLimitsTest {

  private static final int MAX_K = 200;
  private static final int MAX_QUERY_LENGTH = 1000;
  private static final int MAX_QUESTION_LENGTH = 4000;

  private static final SearchResponse EMPTY = new SearchResponse(List.of(), Map.of());

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private SearchService searchService;

  @BeforeEach
  void stubService() {
    when(searchService.search(anyString(), anyString())).thenReturn(EMPTY);
    when(searchService.semanticSearch(anyString(), anyString(), any(), any(), any()))
        .thenReturn(EMPTY);
    when(searchService.hybridSearch(anyString(), anyString(), any(), any(), any()))
        .thenReturn(EMPTY);
    when(searchService.ask(any(AskRequest.class)))
        .thenReturn(new AskResponse("answer", "default", List.of()));
  }

  @Nested
  class QueryLength {

    @Test
    void keywordSearchAcceptsQueryAtLimit() throws Exception {
      mockMvc
          .perform(get("/api/v1/search/books").param("query", "q".repeat(MAX_QUERY_LENGTH)))
          .andExpect(status().isOk());
    }

    @Test
    void keywordSearchRejectsQueryOverLimit() throws Exception {
      mockMvc
          .perform(get("/api/v1/search/books").param("query", "q".repeat(MAX_QUERY_LENGTH + 1)))
          .andExpect(status().isBadRequest())
          .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.status").value(400))
          .andExpect(jsonPath("$.title").value("Input limit exceeded"))
          .andExpect(
              jsonPath("$.detail")
                  .value("query must be at most 1000 characters (search.limits.max-query-length)"))
          .andExpect(jsonPath("$.errors[0].field").value("query"))
          .andExpect(jsonPath("$.errors[0].limit").value("search.limits.max-query-length"))
          .andExpect(jsonPath("$.errors[0].max").value(MAX_QUERY_LENGTH));
      verifyNoInteractions(searchService);
    }

    @Test
    void semanticSearchRejectsQueryOverLimit() throws Exception {
      mockMvc
          .perform(
              get("/api/v1/search/books/semantic").param("query", "q".repeat(MAX_QUERY_LENGTH + 1)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].limit").value("search.limits.max-query-length"));
      verifyNoInteractions(searchService);
    }

    @Test
    void hybridSearchRejectsQueryOverLimit() throws Exception {
      mockMvc
          .perform(
              get("/api/v1/search/books/hybrid").param("query", "q".repeat(MAX_QUERY_LENGTH + 1)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].limit").value("search.limits.max-query-length"));
      verifyNoInteractions(searchService);
    }
  }

  @Nested
  class TopK {

    @Test
    void semanticSearchAcceptsKAtLimit() throws Exception {
      mockMvc
          .perform(
              get("/api/v1/search/books/semantic")
                  .param("query", "java")
                  .param("k", String.valueOf(MAX_K)))
          .andExpect(status().isOk());
      verify(searchService).semanticSearch(eq("books"), eq("java"), eq(MAX_K), any(), any());
    }

    @Test
    void semanticSearchRejectsKOverLimit() throws Exception {
      mockMvc
          .perform(
              get("/api/v1/search/books/semantic")
                  .param("query", "java")
                  .param("k", String.valueOf(MAX_K + 1)))
          .andExpect(status().isBadRequest())
          .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.detail").value("k must be at most 200 (search.limits.max-k)"))
          .andExpect(jsonPath("$.errors[0].field").value("k"))
          .andExpect(jsonPath("$.errors[0].limit").value("search.limits.max-k"))
          .andExpect(jsonPath("$.errors[0].max").value(MAX_K));
      verifyNoInteractions(searchService);
    }

    @Test
    void hybridSearchAcceptsKAtLimit() throws Exception {
      mockMvc
          .perform(
              get("/api/v1/search/books/hybrid")
                  .param("query", "java")
                  .param("k", String.valueOf(MAX_K)))
          .andExpect(status().isOk());
      verify(searchService).hybridSearch(eq("books"), eq("java"), eq(MAX_K), any(), any());
    }

    @Test
    void hybridSearchRejectsKOverLimit() throws Exception {
      mockMvc
          .perform(
              get("/api/v1/search/books/hybrid")
                  .param("query", "java")
                  .param("k", String.valueOf(MAX_K + 1)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].limit").value("search.limits.max-k"));
      verifyNoInteractions(searchService);
    }

    @Test
    void reportsEveryExceededLimit() throws Exception {
      mockMvc
          .perform(
              get("/api/v1/search/books/hybrid")
                  .param("query", "q".repeat(MAX_QUERY_LENGTH + 1))
                  .param("k", String.valueOf(MAX_K + 1)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.length()").value(2));
      verifyNoInteractions(searchService);
    }

    @Test
    void nonPositiveKStillFallsBackToTheDefault() throws Exception {
      // k <= 0 is not a limit breach; SearchService substitutes its default, as before.
      mockMvc
          .perform(get("/api/v1/search/books/hybrid").param("query", "java").param("k", "0"))
          .andExpect(status().isOk());
      verify(searchService).hybridSearch(eq("books"), eq("java"), eq(0), any(), any());
    }
  }

  @Nested
  class QuestionLength {

    @Test
    void askAcceptsQuestionAtLimit() throws Exception {
      mockMvc
          .perform(ask(new AskRequest("q".repeat(MAX_QUESTION_LENGTH), "c-1")))
          .andExpect(status().isOk());
    }

    @Test
    void askRejectsQuestionOverLimit() throws Exception {
      mockMvc
          .perform(ask(new AskRequest("q".repeat(MAX_QUESTION_LENGTH + 1), "c-1")))
          .andExpect(status().isBadRequest())
          .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.title").value("Input limit exceeded"))
          .andExpect(jsonPath("$.errors[0].field").value("question"))
          .andExpect(jsonPath("$.errors[0].limit").value("search.limits.max-question-length"))
          .andExpect(jsonPath("$.errors[0].max").value(MAX_QUESTION_LENGTH));
      verifyNoInteractions(searchService);
    }

    private org.springframework.test.web.servlet.RequestBuilder ask(AskRequest request) {
      return post("/api/v1/search/ask")
          .contentType(MediaType.APPLICATION_JSON)
          .content(objectMapper.writeValueAsString(request));
    }
  }
}
