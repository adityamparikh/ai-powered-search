package dev.aparikh.aipoweredsearch.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W4: {@link AiConfig#rerankChatClient} overrides only the model. The chat model's other defaults
 * (max tokens, temperature) still apply, because ChatClient layers its options over the model's.
 */
class RerankChatClientTest {

    @Test
    void overridesTheModelAndKeepsTheChatModelsOtherDefaults() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(AnthropicChatOptions.builder()
                .model("claude-sonnet-4-5").maxTokens(1234).temperature(0.2).build());
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));

        AiConfig.rerankChatClient(chatModel, "claude-haiku-4-5").prompt().user("rank these").call().content();

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        ChatOptions options = prompt.getValue().getOptions();
        assertThat(options).isNotNull();
        assertThat(options.getModel()).isEqualTo("claude-haiku-4-5");
        assertThat(options.getMaxTokens()).isEqualTo(1234);
        assertThat(options.getTemperature()).isEqualTo(0.2);
    }
}
