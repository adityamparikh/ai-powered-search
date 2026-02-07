package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SearchController.class)
class SearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SearchService searchService;

    // ==================== Traditional Search ====================

    @Nested
    class TraditionalSearch {

        @Test
        void shouldReturnSearchResults() throws Exception {
            String collection = "test-collection";
            String query = "spring boot";

            SearchResponse mockResponse = new SearchResponse(
                    Collections.singletonList(Map.of("id", "1", "name", "Test Document")),
                    Collections.emptyMap()
            );

            when(searchService.search(collection, query)).thenReturn(mockResponse);

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
            mockMvc.perform(get("/api/v1/search/test-collection"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ==================== Hybrid Search ====================

    @Nested
    class HybridSearch {

        @Test
        void shouldReturnHybridSearchResults() throws Exception {
            String collection = "test-collection";
            String query = "machine learning";

            SearchResponse mockResponse = new SearchResponse(
                    List.of(
                            Map.of("id", "1", "content", "ML Framework", "score", 0.032,
                                    "rrf_score", 0.032, "keyword_rank", 1, "vector_rank", 2),
                            Map.of("id", "2", "content", "Deep Learning", "score", 0.016,
                                    "rrf_score", 0.016, "keyword_rank", 2)
                    ),
                    Collections.emptyMap()
            );

            when(searchService.hybridSearch(eq(collection), eq(query), any(), any(), any()))
                    .thenReturn(mockResponse);

            mockMvc.perform(get("/api/v1/search/{collection}/hybrid", collection)
                            .param("query", query))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.documents").isArray())
                    .andExpect(jsonPath("$.documents.length()").value(2))
                    .andExpect(jsonPath("$.documents[0].id").value("1"))
                    .andExpect(jsonPath("$.documents[0].rrf_score").value(0.032))
                    .andExpect(jsonPath("$.documents[0].keyword_rank").value(1))
                    .andExpect(jsonPath("$.documents[0].vector_rank").value(2))
                    .andExpect(jsonPath("$.documents[1].id").value("2"));
        }

        @Test
        void shouldPassTopKParameter() throws Exception {
            String collection = "books";
            String query = "java tutorials";

            SearchResponse mockResponse = new SearchResponse(
                    List.of(Map.of("id", "1", "score", 0.03)),
                    Collections.emptyMap()
            );

            when(searchService.hybridSearch(eq(collection), eq(query), eq(50), any(), any()))
                    .thenReturn(mockResponse);

            mockMvc.perform(get("/api/v1/search/{collection}/hybrid", collection)
                            .param("query", query)
                            .param("k", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.documents").isArray());

            verify(searchService).hybridSearch(collection, query, 50, null, null);
        }

        @Test
        void shouldPassMinScoreParameter() throws Exception {
            String collection = "books";
            String query = "spring boot";

            SearchResponse mockResponse = new SearchResponse(
                    List.of(Map.of("id", "1", "score", 0.05)),
                    Collections.emptyMap()
            );

            when(searchService.hybridSearch(eq(collection), eq(query), any(), eq(0.01), any()))
                    .thenReturn(mockResponse);

            mockMvc.perform(get("/api/v1/search/{collection}/hybrid", collection)
                            .param("query", query)
                            .param("minScore", "0.01"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.documents").isArray());

            verify(searchService).hybridSearch(collection, query, null, 0.01, null);
        }

        @Test
        void shouldPassFieldsParameter() throws Exception {
            String collection = "books";
            String query = "docker";

            SearchResponse mockResponse = new SearchResponse(
                    List.of(Map.of("id", "1", "title", "Docker Guide")),
                    Collections.emptyMap()
            );

            when(searchService.hybridSearch(eq(collection), eq(query), any(), any(), eq("id,title")))
                    .thenReturn(mockResponse);

            mockMvc.perform(get("/api/v1/search/{collection}/hybrid", collection)
                            .param("query", query)
                            .param("fields", "id,title"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.documents[0].title").value("Docker Guide"));

            verify(searchService).hybridSearch(collection, query, null, null, "id,title");
        }

        @Test
        void shouldPassAllParametersTogether() throws Exception {
            String collection = "products";
            String query = "running shoes";

            SearchResponse mockResponse = new SearchResponse(
                    List.of(Map.of("id", "shoe-1", "name", "Ultra Boost")),
                    Collections.emptyMap()
            );

            when(searchService.hybridSearch("products", "running shoes", 20, 0.5, "id,name"))
                    .thenReturn(mockResponse);

            mockMvc.perform(get("/api/v1/search/{collection}/hybrid", collection)
                            .param("query", query)
                            .param("k", "20")
                            .param("minScore", "0.5")
                            .param("fields", "id,name"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.documents[0].name").value("Ultra Boost"));

            verify(searchService).hybridSearch("products", "running shoes", 20, 0.5, "id,name");
        }

        @Test
        void shouldReturnBadRequestWhenQueryIsMissing() throws Exception {
            mockMvc.perform(get("/api/v1/search/test-collection/hybrid"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void shouldReturnEmptyResultsGracefully() throws Exception {
            String collection = "books";
            String query = "nonexistent topic xyz";

            SearchResponse mockResponse = new SearchResponse(
                    Collections.emptyList(),
                    Collections.emptyMap()
            );

            when(searchService.hybridSearch(eq(collection), eq(query), any(), any(), any()))
                    .thenReturn(mockResponse);

            mockMvc.perform(get("/api/v1/search/{collection}/hybrid", collection)
                            .param("query", query))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.documents").isArray())
                    .andExpect(jsonPath("$.documents").isEmpty());
        }

        @Test
        void shouldHandleDefaultParameters() throws Exception {
            String collection = "books";
            String query = "ai search";

            SearchResponse mockResponse = new SearchResponse(
                    List.of(Map.of("id", "1", "score", 0.03)),
                    Collections.emptyMap()
            );

            when(searchService.hybridSearch(eq(collection), eq(query), any(), any(), any()))
                    .thenReturn(mockResponse);

            mockMvc.perform(get("/api/v1/search/{collection}/hybrid", collection)
                            .param("query", query))
                    .andExpect(status().isOk());

            // k, minScore, and fields should be null when not provided
            verify(searchService).hybridSearch(collection, query, null, null, null);
        }
    }
}
