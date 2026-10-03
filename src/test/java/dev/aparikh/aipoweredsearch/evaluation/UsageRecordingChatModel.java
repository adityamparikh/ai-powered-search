package dev.aparikh.aipoweredsearch.evaluation;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Decorates the application's {@link ChatModel} to total token usage across every model call
 * an {@code /ask} makes: generation, reranking and, once later stages land, planning.
 *
 * <p>Wrapping the model rather than reading the final {@code ChatResponse} is what makes the
 * reranker's call visible: it uses its own {@code ChatClient}, so its usage never reaches the
 * response returned to the caller.</p>
 */
public class UsageRecordingChatModel implements ChatModel {

    /** Token totals since the last {@link #reset()}. */
    public record Totals(long calls, long promptTokens, long completionTokens) {
        public long totalTokens() {
            return promptTokens + completionTokens;
        }
    }

    private final ChatModel delegate;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();

    public UsageRecordingChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse response = delegate.call(prompt);
        calls.incrementAndGet();
        if (response != null && response.getMetadata() != null) {
            Usage usage = response.getMetadata().getUsage();
            if (usage != null) {
                promptTokens.addAndGet(nullToZero(usage.getPromptTokens()));
                completionTokens.addAndGet(nullToZero(usage.getCompletionTokens()));
            }
        }
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt);
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    public Totals snapshot() {
        return new Totals(calls.get(), promptTokens.get(), completionTokens.get());
    }

    public void reset() {
        calls.set(0);
        promptTokens.set(0);
        completionTokens.set(0);
    }

    private static long nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}
