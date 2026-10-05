package dev.aparikh.aipoweredsearch.config;

import dev.aparikh.aipoweredsearch.search.HybridDocumentRetriever;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A reranker provider other than Claude that has no Jev reranker behind it (a typo, or no TypeSafe
 * key) falls back to Claude, and says so rather than silently.
 */
@ExtendWith(OutputCaptureExtension.class)
class RerankerSelectionTest {

    private void ragChatClientWithProvider(String provider) {
        new AiConfig().ragChatClient(mock(ChatModel.class), mock(ChatMemory.class),
                mock(HybridDocumentRetriever.class), false, null, null, query -> query, null, null, provider);
    }

    @Test
    void anUnusableProviderFallsBackToClaudeWithAWarning(CapturedOutput output) {
        ragChatClientWithProvider("jevv");

        assertThat(output).contains("search.rag.rerank.provider=jevv needs 'jev' and spring.ai.typesafe.api-key");
    }

    @Test
    void theDefaultProviderDoesNotWarn(CapturedOutput output) {
        ragChatClientWithProvider("claude");

        assertThat(output).doesNotContain("search.rag.rerank.provider=");
    }
}
