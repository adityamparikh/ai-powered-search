package dev.aparikh.aipoweredsearch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.aparikh.aipoweredsearch.search.HybridDocumentRetriever;
import dev.aparikh.aipoweredsearch.search.SearchRepository;
import dev.aparikh.aipoweredsearch.search.SearchService;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatProperties;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Issue #74: with the default profile, no question, prompt, retrieved passage or answer that passes
 * through the application's advisor chain reaches the logs.
 *
 * <p>Each test starts the real {@link AiConfig} wiring under {@link SpringApplicationBuilder}, so
 * the logging levels come from the real {@code application.properties} (and, for the {@code local}
 * test, {@code application-local.properties}). A turn then runs through the real advisor chain:
 * query rewriting, {@link HybridDocumentRetriever}, reranking, chat memory, {@link
 * SimpleLoggerAdvisor} and {@link PromptCacheMetricsAdvisor}. The question, the passage and the
 * answer each carry a marker, and the test looks for the markers in everything written to the
 * console.
 *
 * <p>The {@code local} test is the control: the same turn there must log every marker, so the
 * default test cannot pass merely because the scenario logs nothing at all.
 *
 * <p>What it does not cover. The chat model is a stub, the chat memory is in memory, {@code
 * SearchRepository} is a mock, and Spring AI's auto-configuration does not run. So it does not see
 * the framework's own loggers ({@code AnthropicChatModel}, the OpenAI embedding model), the
 * observation handlers that the {@code spring.ai.*.observations.*} properties switch, or Solr's
 * failure paths. For {@code AnthropicChatModel} it only checks that the configured level resolves
 * to ERROR; {@code SolrErrorLoggingTest} covers the Solr failure paths.
 */
@ExtendWith(OutputCaptureExtension.class)
// The local test runs first so that the levels left behind for later test classes in this JVM are
// the default ones. Each test reinitialises logging, so the order does not affect the assertions.
//
// Isolation: each test resets Logback, which is global to the JVM. That is safe only while JUnit
// runs test classes one at a time, as the build does today. With parallel class execution, this
// test would reset logging under another class that is running, and vice versa.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ContentLoggingTest {

  // Plain identifiers: a JSON parser quotes an unrecognised token up to the first non-identifier
  // character, so a marker at the start of the model's reply ends up whole in the parse error.
  static final String QUESTION_MARKER = "QuestionMarker7f3a";
  static final String FOLLOW_UP_MARKER = "FollowUpMarker2b6c";
  static final String PASSAGE_MARKER = "PassageMarker91c2";
  static final String ANSWER_MARKER = "AnswerMarker4d8e";
  static final String SEARCH_QUERY_MARKER = "SearchQueryMarker5e1f";
  static final List<String> MARKERS =
      List.of(
          QUESTION_MARKER, FOLLOW_UP_MARKER, PASSAGE_MARKER, ANSWER_MARKER, SEARCH_QUERY_MARKER);

  /** Every prompt the stub chat model received, across all of a run's chat clients. */
  static final List<String> PROMPTS = new CopyOnWriteArrayList<>();

  @Test
  @Order(1)
  void theLocalProfileLogsPromptsPassagesAndAnswers(CapturedOutput output) {
    String answer = runTurns("local");

    assertThat(answer).contains(ANSWER_MARKER);
    assertThat(output.getAll()).contains(MARKERS);
  }

  @Test
  @Order(2)
  void theDefaultProfileLogsNoPromptPassageOrAnswer(CapturedOutput output) {
    String answer = runTurns();

    // The content was in flight: the answer reached the caller, the passage reached the prompt,
    // and the reranker failed on a reply that quotes the passage.
    assertThat(answer).contains(ANSWER_MARKER);
    assertThat(PROMPTS).anySatisfy(prompt -> assertThat(prompt).contains(PASSAGE_MARKER));
    assertThat(output.getAll()).contains("Reranking failed (");

    for (String marker : MARKERS) {
      assertThat(output.getAll())
          .as("console output must not contain %s", marker)
          .doesNotContain(marker);
    }
  }

  /**
   * Starts the application wiring with the given profiles, asks a question and a follow-up through
   * the RAG chat client and a query through the search chat client, and returns the last answer.
   */
  private static String runTurns(String... profiles) {
    PROMPTS.clear();
    // Earlier contexts in this JVM leave Logback marked as initialised, and Spring Boot then only
    // adds levels on top of theirs. Clearing the mark makes this run reset logging and apply the
    // levels from the properties files alone.
    LoggingSystem.get(ContentLoggingTest.class.getClassLoader()).cleanUp();

    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(Stubs.class, AiConfig.class, HybridDocumentRetriever.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .profiles(profiles)
            .run()) {

      boolean local = profiles.length > 0;
      assertThat(LoggerFactory.getLogger(SimpleLoggerAdvisor.class).isDebugEnabled())
          .isEqualTo(local);
      assertThat(LoggerFactory.getLogger(SearchService.class).isDebugEnabled()).isEqualTo(local);
      // Its WARNs quote the whole prompt; the stub model cannot trigger them, so check the level.
      assertThat(LoggerFactory.getLogger(AnthropicChatModel.class).isWarnEnabled())
          .isEqualTo(local);
      assertThat(LoggerFactory.getLogger(AnthropicChatModel.class).isErrorEnabled()).isTrue();

      ChatClient rag = context.getBean("ragChatClient", ChatClient.class);
      // A conversation ID, so the follow-up goes through the query rewrite.
      String conversationId = "content-logging";
      rag.prompt()
          .user("Which book covers " + QUESTION_MARKER + "?")
          .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
          .call()
          .content();
      String answer =
          rag.prompt()
              .user("And " + FOLLOW_UP_MARKER + "?")
              .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
              .call()
              .content();

      context
          .getBean("searchChatClient", ChatClient.class)
          .prompt()
          .user("books about " + SEARCH_QUERY_MARKER)
          .call()
          .content();
      return answer == null ? "" : answer;
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class Stubs {

    /**
     * Rewrites a follow-up into a standalone query, answers a ranking request with prose that
     * quotes the passage (so reranking fails to parse it), and answers everything else.
     */
    @Bean
    ChatModel chatModel() {
      return prompt -> {
        String contents = prompt.getContents();
        PROMPTS.add(contents);
        String reply;
        if (contents.contains("standalone query")) {
          reply = "Standalone question about " + QUESTION_MARKER + " and " + FOLLOW_UP_MARKER;
        } else if (contents.startsWith("Rank the following documents")) {
          reply = PASSAGE_MARKER + " is the most relevant document.";
        } else {
          reply = "The answer is " + ANSWER_MARKER + ".";
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
      };
    }

    @Bean
    ChatMemory chatMemory() {
      return MessageWindowChatMemory.builder().build();
    }

    @Bean
    SearchRepository searchRepository() {
      SearchRepository searchRepository = mock(SearchRepository.class);
      when(searchRepository.executeHybridRerankSearch(
              anyString(), anyString(), anyInt(), any(), any(), any()))
          .thenReturn(
              new SearchResponse(
                  List.of(
                      Map.of("id", "doc-1", "content", "A passage about " + PASSAGE_MARKER + ".")),
                  Map.of()));
      return searchRepository;
    }

    /** Keeps AiConfig from building an OpenAI client, which would need a key. */
    @Bean
    EmbeddingModel embeddingModel() {
      return mock(EmbeddingModel.class);
    }

    /** Normally bound by the Anthropic auto-configuration, which this context does not run. */
    @Bean
    AnthropicChatProperties anthropicChatProperties() {
      return new AnthropicChatProperties();
    }
  }
}
