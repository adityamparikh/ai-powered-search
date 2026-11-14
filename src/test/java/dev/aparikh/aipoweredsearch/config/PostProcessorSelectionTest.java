package dev.aparikh.aipoweredsearch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.aparikh.aipoweredsearch.search.HybridDocumentRetriever;
import dev.aparikh.aipoweredsearch.search.RerankingDocumentPostProcessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.decision.DecisionModel;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Warnings are matched as {@code "] WARN "}, the log-level token, because Mockito's own JVM-agent
 * notice also contains the word "WARNING".
 *
 * <p>Asking for a decisions stage that no {@link DecisionModel} supplies (no System One key, {@code
 * spring.ai.model.decision=none}, a typo) must be loud: unscreened passages or Claude reranking are
 * not what was asked for.
 */
@ExtendWith(OutputCaptureExtension.class)
class PostProcessorSelectionTest {

  private final RerankingDocumentPostProcessor claudeReranker =
      mock(RerankingDocumentPostProcessor.class);

  private void ragChatClient(
      boolean decisionsEnabled,
      @Nullable DecisionModel decisionModel,
      String rerankProvider,
      @Nullable RerankingDocumentPostProcessor reranker) {
    new AiConfig()
        .ragChatClient(
            mock(ChatModel.class),
            mock(ChatMemory.class),
            mock(HybridDocumentRetriever.class),
            false,
            null,
            reranker,
            query -> query,
            decisionModel,
            decisionsEnabled,
            rerankProvider,
            5,
            0.0,
            4);
  }

  @Test
  void decisionsEnabledWithoutAModelWarnsThatPassagesAreNotScreened(CapturedOutput output) {
    ragChatClient(true, null, "claude", claudeReranker);

    assertThat(output)
        .contains("search.rag.decisions.enabled=true but no DecisionModel is configured");
  }

  @Test
  void decisionsEnabledWithAModelScreensWithoutWarning(CapturedOutput output) {
    ragChatClient(true, mock(DecisionModel.class), "claude", claudeReranker);

    assertThat(output)
        .doesNotContain("] WARN ")
        .contains("decisions filter DecisionDocumentFilter");
  }

  @Test
  void theDecisionsRerankProviderRerankWithTheDecisionModel(CapturedOutput output) {
    ragChatClient(false, mock(DecisionModel.class), "decisions", claudeReranker);

    assertThat(output).doesNotContain("] WARN ").contains("reranker DecisionDocumentReranker");
  }

  @Test
  void theDecisionsRerankProviderWithoutAModelFallsBackToClaudeWithAWarning(CapturedOutput output) {
    ragChatClient(false, null, "decisions", claudeReranker);

    assertThat(output)
        .contains("search.rag.rerank.provider=decisions but no DecisionModel is configured");
  }

  @Test
  void anUnknownRerankProviderFallsBackToClaudeWithAWarning(CapturedOutput output) {
    ragChatClient(false, null, "decisons", claudeReranker);

    assertThat(output)
        .contains("search.rag.rerank.provider=decisons is neither 'claude' nor 'decisions'");
  }

  @Test
  void rerankingDisabledStaysDisabledWhateverTheProvider(CapturedOutput output) {
    // search.rag.rerank.enabled=false leaves no Claude reranker bean, and nothing replaces it.
    ragChatClient(false, mock(DecisionModel.class), "decisions", null);

    assertThat(output).contains("reranker off");
  }

  @Test
  void theDefaultsDoNotWarn(CapturedOutput output) {
    ragChatClient(false, null, "claude", claudeReranker);

    assertThat(output).doesNotContain("] WARN ");
  }
}
