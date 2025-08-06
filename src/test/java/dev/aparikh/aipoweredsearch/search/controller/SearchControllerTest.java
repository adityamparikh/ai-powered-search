package dev.aparikh.aipoweredsearch.search.controller;

import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import dev.aparikh.aipoweredsearch.search.service.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SearchController.class)
class SearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SearchService searchService;

    @Test
    void shouldReturnSearchResults() throws Exception {
        // Given
        String collection = "test-collection";
        String query = "spring boot";
        
        SearchResponse mockResponse = new SearchResponse(
                Collections.singletonList(Map.of("id", "1", "name", "Test Document")),
                Collections.emptyMap()
        );
        
        when(searchService.search(collection, query)).thenReturn(mockResponse);
        
        // When/Then
        mockMvc.perform(get("/api/v1/search/{collection}", collection)
                .param("query", query))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").isArray())
                .andExpect(jsonPath("$.documents[0].id").value("1"))
                .andExpect(jsonPath("$.documents[0].name").value("Test Document"))
                .andExpect(jsonPath("$.facetCounts").isEmpty());
    }
    
    @Test
    void shouldReturnBadRequestWhenQueryIsMissing() throws Exception {
        // When/Then
        mockMvc.perform(get("/api/v1/search/test-collection"))
                .andExpect(status().isBadRequest());
    }
}