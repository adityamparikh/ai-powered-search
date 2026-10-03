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
 *
 * <p>What it does not count:</p>
 * <ul>
 *   <li>{@link #stream(Prompt)} calls, which pass through untotalled. {@code /ask} only uses
 *       {@code call()}.</li>
 *   <li>Anthropic prompt-cache tokens. Anthropic reports cache-read and cache-creation tokens
 *       separately from input tokens, so cached prompt content is not in the total.</li>
 * </ul>
 *
 * <p>{@link #reset()} and {@link #snapshot()} are not atomic across the two counters; that is
 * fine because cases run one at a time.</p>
 */
public class UsageRecordingChatModel implements ChatModel {

    /**
     * Token totals since the last {@link #reset()}.
     *
     * @param tokens prompt plus completion tokens, excluding prompt-cache tokens
     */
    public record Totals(long calls, long tokens) {
    }

    private final ChatModel delegate;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong tokens = new AtomicLong();

    public UsageRecordingChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse response = delegate.call(prompt);
        calls.incrementAndGet();
        // Metadata is never null, but a provider can set a null Usage on it.
        Usage usage = response.getMetadata().getUsage();
        if (usage != null) {
            tokens.addAndGet(nullToZero(usage.getPromptTokens()) + nullToZero(usage.getCompletionTokens()));
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
        return new Totals(calls.get(), tokens.get());
    }

    public void reset() {
        calls.set(0);
        tokens.set(0);
    }

    private static long nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}
