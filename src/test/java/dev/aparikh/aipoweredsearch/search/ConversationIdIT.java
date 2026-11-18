package dev.aparikh.aipoweredsearch.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import dev.aparikh.aipoweredsearch.search.model.AskRequest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * Conversation IDs on an existing database, through {@code POST /ask} with real Postgres chat
 * memory. Rows written before the server issued IDs, such as the old shared {@code "default"}
 * conversation and client-chosen IDs, still have history, so having history alone cannot mean the
 * server issued an ID. Needs no API keys: the chat model is a stub that counts its calls.
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, SolrTestConfiguration.class, ConversationIdIT.Stub.class})
class ConversationIdIT {

  static final AtomicInteger MODEL_CALLS = new AtomicInteger();

  @TestConfiguration(proxyBeanMethods = false)
  static class Stub {

    @Bean
    @Primary
    ChatModel countingChatModel() {
      return prompt -> {
        MODEL_CALLS.incrementAndGet();
        return new ChatResponse(List.of(new Generation(new AssistantMessage("stub answer"))));
      };
    }

    @Bean
    @Primary
    HybridDocumentRetriever emptyRetriever(SearchRepository searchRepository) {
      // Keeps Solr and embeddings out of the accepted-ID path.
      return new HybridDocumentRetriever(searchRepository, "unused", 20) {
        @Override
        public List<Document> retrieve(Query query) {
          return List.of();
        }
      };
    }
  }

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private ChatMemory chatMemory;

  @Autowired private JdbcTemplate jdbcTemplate;

  @ParameterizedTest
  @ValueSource(strings = {"default", "user-123"})
  void aRowFromBeforeTheServerIssuedIdsIsNotReachable(String legacyId) throws Exception {
    chatMemory.clear(legacyId);
    chatMemory.add(legacyId, new UserMessage("PRIVATE: my account number is 12345"));
    List<Message> seeded = chatMemory.get(legacyId);
    int modelCallsBefore = MODEL_CALLS.get();

    ask(new AskRequest("What did I say before?", legacyId))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Conversation not found"));

    assertThat(MODEL_CALLS).hasValue(modelCallsBefore);
    // Nothing was appended: the question did not join the old history.
    assertThat(chatMemory.get(legacyId)).isEqualTo(seeded);
  }

  /** The cleanup documented in CLAUDE.md (Chat Memory) for rows from before server-issued IDs. */
  static final String LEGACY_CLEANUP_SQL =
      "DELETE FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id !~"
          + " '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'";

  @Test
  void theDocumentedCleanupRemovesOnlyRowsTheServerCouldNotHaveIssued() {
    String issued = UUID.randomUUID().toString();
    chatMemory.add("user-123", new UserMessage("legacy"));
    chatMemory.add(issued, new UserMessage("current"));

    jdbcTemplate.update(LEGACY_CLEANUP_SQL);

    assertThat(chatMemory.get("user-123")).isEmpty();
    assertThat(chatMemory.get(issued)).hasSize(1);
  }

  @Test
  void aNulByteInTheIdIsA404NotA500() throws Exception {
    // Postgres cannot store NUL in text; the ID must be rejected before it reaches the database.
    ask(new AskRequest("q", "3f2b8c1e-7a4d-4e9b-9c6a-1d2e3f4a\0b6c"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void anIssuedIdIsAccepted() throws Exception {
    String body =
        ask(new AskRequest("Recommend a fantasy series."))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String conversationId = objectMapper.readTree(body).get("conversationId").asString();

    ask(new AskRequest("Anything shorter?", conversationId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conversationId").value(conversationId));
  }

  private ResultActions ask(AskRequest request) throws Exception {
    return mockMvc.perform(
        post("/api/v1/search/ask")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)));
  }
}
