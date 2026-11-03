package dev.aparikh.aipoweredsearch.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRewriteTest {

    private static final String FOLLOW_UP = "Anything cheaper by the same author?";
    private static final String STANDALONE = "Books by George R.R. Martin cheaper than A Game of Thrones";

    private final AtomicInteger modelCalls = new AtomicInteger();

    private QueryTransformer transformer(ChatModel model) {
        return new AiConfig().queryTransformer(model, "test-model");
    }

    private ChatModel answering(String text) {
        return prompt -> {
            modelCalls.incrementAndGet();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        };
    }

    /** A follow-up as RetrievalAugmentationAdvisor builds it: history ends with the current message. */
    private static Query followUp(String conversationId) {
        Map<String, Object> context = new HashMap<>();
        context.put(ChatMemory.CONVERSATION_ID, conversationId);
        return Query.builder()
                .text(FOLLOW_UP)
                .history(List.of(
                        new UserMessage("Recommend an epic fantasy series with political intrigue."),
                        new AssistantMessage("Try A Game of Thrones by George R.R. Martin."),
                        new UserMessage(FOLLOW_UP)))
                .context(context)
                .build();
    }

    @Test
    void aFollowUpIsRewrittenAndTheRewriteHandedOn() {
        Query original = followUp("conversation-1");

        Query rewritten = transformer(answering(STANDALONE)).transform(original);

        assertThat(rewritten.text()).isEqualTo(STANDALONE);
        assertThat(original.context()).containsEntry(AiConfig.STANDALONE_QUERY, STANDALONE);
    }

    @Test
    void aFailingRewriteFallsBackToTheQuestionAsAsked() {
        ChatModel failing = prompt -> {
            throw new IllegalStateException("model overloaded");
        };
        Query original = followUp("conversation-1");

        assertThat(transformer(failing).transform(original)).isSameAs(original);
        assertThat(original.context()).doesNotContainKey(AiConfig.STANDALONE_QUERY);
    }

    @Test
    void theSharedDefaultConversationIsNotRewritten() {
        // Requests without a conversationId share "default", so its history mixes unrelated callers.
        Query original = followUp("default");

        assertThat(transformer(answering(STANDALONE)).transform(original)).isSameAs(original);
        assertThat(modelCalls).hasValue(0);
    }

    @Test
    void aSystemPromptIsNotAnEarlierTurn() {
        assertThat(AiConfig.hasEarlierTurns(List.of(new SystemMessage("You are a librarian."),
                new UserMessage("Recommend a fantasy series.")))).isFalse();
        assertThat(AiConfig.hasEarlierTurns(followUp("c").history())).isTrue();
    }
}
