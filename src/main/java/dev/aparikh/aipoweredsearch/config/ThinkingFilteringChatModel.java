package dev.aparikh.aipoweredsearch.config;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * A {@link ChatModel} that keeps a model's thinking blocks from hiding its answer.
 *
 * <p>Claude 5 models think by default, and which of them can be told not to differs: Sonnet 5.5
 * accepts only {@code thinking.type=between_tools}, Haiku 5.5 only {@code disabled}, and Opus 5.5
 * and Fable 5.1 cannot turn it off at all. Spring AI hands each thinking block back as its own
 * {@link Generation}, <em>ahead of</em> the answer, with blank text. {@code ChatResponse#getResult()}
 * reads the first generation, so {@code ChatClient...content()} and {@code ...entity()} then see a
 * blank answer, or fail to parse it as JSON.</p>
 *
 * <p>This drops blank, tool-call-free generations whenever another generation has text. It does
 * not depend on the model, on what the thinking metadata is called, or on the model being told to
 * stop thinking, so it holds as models are added. A response with nothing but blank generations
 * is left alone, so a genuinely empty answer is still visible as one.</p>
 */
final class ThinkingFilteringChatModel implements ChatModel {

    private final ChatModel delegate;

    ThinkingFilteringChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return withoutThinking(delegate.call(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt).map(ThinkingFilteringChatModel::withoutThinking);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    static ChatResponse withoutThinking(ChatResponse response) {
        if (response == null || response.getResults().size() < 2) {
            return response;
        }
        List<Generation> answers = response.getResults().stream()
                .filter(ThinkingFilteringChatModel::isAnswer)
                .toList();
        if (answers.isEmpty() || answers.size() == response.getResults().size()) {
            return response;
        }
        return ChatResponse.builder().from(response).generations(answers).build();
    }

    /** A generation is part of the answer if it has text, or asks for a tool call. */
    private static boolean isAnswer(Generation generation) {
        AssistantMessage message = generation.getOutput();
        return message.hasToolCalls() || (message.getText() != null && !message.getText().isBlank());
    }
}
