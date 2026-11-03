package dev.aparikh.aipoweredsearch.config;

import dev.aparikh.aipoweredsearch.search.HybridDocumentRetriever;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Asking for a decisions stage that no provider supplies (a typo, no TypeSafe key, or no provider
 * configured) must be loud: unscreened passages or Claude reranking are not what was asked for.
 */
@ExtendWith(OutputCaptureExtension.class)
class PostProcessorSelectionTest {

    private void ragChatClient(boolean decisionsEnabled, DocumentPostProcessor decisionsFilter, String rerankProvider) {
        new AiConfig().ragChatClient(mock(ChatModel.class), mock(ChatMemory.class),
                mock(HybridDocumentRetriever.class), false, null, null, query -> query,
                decisionsFilter, null, decisionsEnabled, rerankProvider);
    }

    @Test
    void decisionsEnabledWithoutAProviderWarnsThatPassagesAreNotScreened(CapturedOutput output) {
        ragChatClient(true, null, "claude");

        assertThat(output).contains("search.rag.decisions.enabled=true but no decisions provider is configured");
    }

    @Test
    void decisionsEnabledWithAProviderDoesNotWarn(CapturedOutput output) {
        ragChatClient(true, (query, documents) -> documents, "claude");

        assertThat(output).doesNotContain("no decisions provider");
    }

    @Test
    void aRerankProviderWithoutADecisionsRerankerFallsBackToClaudeWithAWarning(CapturedOutput output) {
        ragChatClient(false, null, "decisons");

        assertThat(output).contains("search.rag.rerank.provider=decisons has no decisions reranker");
    }

    @Test
    void theDefaultsDoNotWarn(CapturedOutput output) {
        ragChatClient(false, null, "claude");

        assertThat(output).doesNotContain("WARN");
    }
}
