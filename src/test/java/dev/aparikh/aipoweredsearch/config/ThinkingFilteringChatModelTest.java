package dev.aparikh.aipoweredsearch.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the contract of {@link ThinkingFilteringChatModel}.
 *
 * <p>Claude 5 models think by default, and Spring AI returns the thinking block as its own
 * {@link Generation} <em>ahead of</em> the answer, with blank text. {@code ChatResponse.getResult()}
 * (and so {@code ChatClient...content()} and {@code ...entity()}) reads the first generation, so
 * without the filter an answer comes back blank.</p>
 */
class ThinkingFilteringChatModelTest {

    private static Generation text(String text) {
        return new Generation(new AssistantMessage(text));
    }

    /** What Spring AI 2.0.1 produces for a thinking block: blank text, signature in the metadata. */
    private static Generation thinking() {
        return new Generation(AssistantMessage.builder()
                .content("")
                .properties(Map.of("signature", "opaque-signature"))
                .build());
    }

    private static ChatModel returning(Generation... generations) {
        ChatResponse response = new ChatResponse(List.of(generations));
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return response;
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(response);
            }
        };
    }

    @Test
    void aLeadingThinkingGenerationDoesNotHideTheAnswer() {
        ChatModel model = new ThinkingFilteringChatModel(returning(thinking(), text("O'Reilly Media")));

        ChatResponse response = model.call(new Prompt("who published it?"));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("O'Reilly Media");
        assertThat(response.getResults()).hasSize(1);
    }

    @Test
    void theAnswerIsReadableThroughTheModelInterfaceToo() {
        ChatModel model = new ThinkingFilteringChatModel(returning(thinking(), text("O'Reilly Media")));

        assertThat(model.call("who published it?")).isEqualTo("O'Reilly Media");
    }

    @Test
    void aResponseWithoutThinkingIsReturnedUntouched() {
        ChatModel delegate = returning(text("plain answer"));

        ChatResponse response = new ThinkingFilteringChatModel(delegate).call(new Prompt("q"));

        assertThat(response).isSameAs(delegate.call(new Prompt("q")));
    }

    @Test
    void aResponseWhoseGenerationsAreAllBlankIsLeftAloneSoAnEmptyAnswerStaysVisible() {
        ChatModel model = new ThinkingFilteringChatModel(returning(thinking()));

        ChatResponse response = model.call(new Prompt("q"));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResult().getOutput().getText()).isEmpty();
    }

    @Test
    void aBlankGenerationThatCarriesToolCallsIsNotThinkingAndIsKept() {
        Generation toolCall = new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("id-1", "function", "lookup", "{}")))
                .build());
        ChatModel model = new ThinkingFilteringChatModel(returning(toolCall, text("done")));

        ChatResponse response = model.call(new Prompt("q"));

        assertThat(response.getResults()).hasSize(2);
        assertThat(response.getResult().getOutput().hasToolCalls()).isTrue();
    }

    @Test
    void streamedChunksAreFilteredToo() {
        ChatModel model = new ThinkingFilteringChatModel(returning(thinking(), text("chunk")));

        List<ChatResponse> chunks = model.stream(new Prompt("q")).collectList().block();

        assertThat(chunks).singleElement().satisfies(chunk ->
                assertThat(chunk.getResult().getOutput().getText()).isEqualTo("chunk"));
    }

    @Test
    void theWrapperIsTransparentToDefaultOptions() {
        ChatOptions options = ChatOptions.builder().model("claude-sonnet-5-5").build();
        ChatModel delegate = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(text("x")));
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return options;
            }
        };

        assertThat(new ThinkingFilteringChatModel(delegate).getDefaultOptions()).isSameAs(options);
    }
}
