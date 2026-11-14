package dev.aparikh.aipoweredsearch.search;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import dev.aparikh.aipoweredsearch.search.model.AskRequest;
import dev.aparikh.aipoweredsearch.search.model.AskResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * {@code search.rag.rerank.provider=decisions} against the real TypeSafe API (Jev). Complements
 * {@link DecisionModelRerankingIT}, which only covers the unreachable-API fallback.
 *
 * <p>Jev reranks fixed candidates (one that answers the question among unrelated ones) and must
 * keep {@code top-k} of them with the answering passage first. Needs {@code TYPESAFE_API_KEY}; the
 * chat model is a stub, so only the Jev calls are billed.
 */
@EnabledIfEnvironmentVariable(named = "TYPESAFE_API_KEY", matches = ".+")
@SpringBootTest(
    properties = {
      "spring.ai.openai.api-key=test-key",
      "search.rag.rerank.provider=decisions",
      "search.rag.rerank.top-k=2",
      "search.rag.decisions.systemone.api-key=${TYPESAFE_API_KEY}"
    })
@Import({
  PostgresTestConfiguration.class,
  SolrTestConfiguration.class,
  DecisionModelRerankingLiveIT.StubModels.class
})
@ExtendWith(OutputCaptureExtension.class)
class DecisionModelRerankingLiveIT {

  static final String ANSWER = "clash";

  // The answering passage is deliberately not first, so keeping it first proves Jev reordered.
  static final Map<String, String> CANDIDATES =
      Map.of(
          "cook",
          "Simmer the onions in butter for ten minutes, then add the stock and a bay leaf.",
          "python",
          "Python lists are mutable sequences; use append() to add an element at the end.",
          ANSWER,
          "A Clash of Kings is the second novel in A Song of Ice and Fire, written by George R. R."
              + " Martin and published in 1998.",
          "tide",
          "Ocean tides are caused by the gravitational pull of the moon and the sun.");

  static final List<String> RETRIEVED = List.of("cook", "python", "tide", ANSWER);

  @TestConfiguration(proxyBeanMethods = false)
  static class StubModels {

    @Bean
    @Primary
    ChatModel stubChatModel() {
      return prompt ->
          new ChatResponse(List.of(new Generation(new AssistantMessage("stub answer"))));
    }

    @Bean
    @Primary
    HybridDocumentRetriever fixedRetriever(SearchRepository searchRepository) {
      return new HybridDocumentRetriever(searchRepository, "unused", 20) {
        @Override
        public List<Document> retrieve(Query query) {
          return RETRIEVED.stream()
              .map(id -> new Document(id, CANDIDATES.get(id), Map.of()))
              .toList();
        }
      };
    }
  }

  @Autowired private SearchService searchService;

  @Test
  void jevKeepsTopKWithTheAnsweringPassageFirst(CapturedOutput output) {
    AskResponse response =
        searchService.ask(new AskRequest("Who wrote A Clash of Kings?", "jev-rerank-live"));

    assertThat(output).doesNotContain("Could not score");
    assertThat(response.sources()).hasSize(2).first().isEqualTo(ANSWER);
  }
}
