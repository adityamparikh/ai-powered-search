package dev.aparikh.aipoweredsearch.config;

import dev.aparikh.aipoweredsearch.search.SearchService;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the Spring Framework 7 path-segment API versioning wired up through
 * {@code spring.mvc.apiversion.*}.
 *
 * <p>The version resolver reads path segment 1 of every request handled by
 * {@code RequestMappingHandlerMapping}, and a segment it cannot parse becomes a 400. The
 * interesting question is therefore not whether {@code /api/v1/...} still routes, but
 * whether paths carrying no version — actuator, the OpenAPI docs — are caught in the
 * crossfire, since those are served by separate handler mappings.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({PostgresTestConfiguration.class, ApiVersioningTest.TestConfig.class})
@ActiveProfiles("it")
class ApiVersioningTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SearchService searchService;

    @Test
    void routesTheExistingV1PathUnchanged() throws Exception {
        when(searchService.search(anyString(), anyString()))
                .thenReturn(new SearchResponse(List.of(Map.of("id", "1")), Map.of()));

        mockMvc.perform(get("/api/v1/search/books").param("query", "spring"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsAnUnsupportedVersion() throws Exception {
        mockMvc.perform(get("/api/v9/search/books").param("query", "spring"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void leavesActuatorReachable() throws Exception {
        // /actuator/health has "health" at path segment 1, which is not a parseable version.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void leavesTheOpenApiDocsReachable() throws Exception {
        // /api-docs has no path segment 1 at all.
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk());
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        ChatModel mockChatModel() {
            ChatModel chatModel = mock(ChatModel.class);
            // Spring AI 2.x seeds a ChatClient's defaults from chatModel.getOptions().mutate(),
            // so the mock must return real options rather than Mockito's default null.
            when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
            return chatModel;
        }

        @Bean
        @Primary
        EmbeddingModel mockEmbeddingModel() {
            return mock(EmbeddingModel.class);
        }
    }
}
