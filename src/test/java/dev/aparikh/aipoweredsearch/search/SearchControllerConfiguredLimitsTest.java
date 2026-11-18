package dev.aparikh.aipoweredsearch.search;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The limits come from configuration, not from constants in the annotations. */
@WebMvcTest(
    controllers = SearchController.class,
    properties = {"search.limits.max-k=150", "search.limits.max-query-length=10"})
class SearchControllerConfiguredLimitsTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SearchService searchService;

  @Test
  void enforcesConfiguredQueryLength() throws Exception {
    mockMvc
        .perform(get("/api/v1/search/books").param("query", "q".repeat(10)))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/v1/search/books").param("query", "q".repeat(11)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].max").value(10));
  }

  @Test
  void enforcesConfiguredMaxK() throws Exception {
    mockMvc
        .perform(get("/api/v1/search/books/hybrid").param("query", "java").param("k", "150"))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/v1/search/books/hybrid").param("query", "java").param("k", "151"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].max").value(150));
  }
}
