package dev.aparikh.aipoweredsearch.search;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the {@link RetrievalAugmentationAdvisor} behaviour (Spring AI 2.0.1) that the modular-RAG
 * stages of epic #32 rely on. These are W0 findings A1, kept as regression tests so a Spring AI
 * upgrade that changes them fails here rather than silently degrading {@code /ask}.
 */
class RetrievalAugmentationAdvisorContractTest {

    private static final String STANDALONE_KEY = "rag.standalone";

    private final AtomicReference<Query> retrieved = new AtomicReference<>();
    private final AtomicReference<Query> postProcessed = new AtomicReference<>();
    private final List<String> retrieverThreads = new CopyOnWriteArrayList<>();

    private final DocumentRetriever recordingRetriever = query -> {
        retrieved.set(query);
        retrieverThreads.add(Thread.currentThread().getName());
        return List.of(new Document("doc-1", "A Game of Thrones", Map.of()));
    };

    /** An expander that writes into the original query's context and returns a rewritten query. */
    private final QueryExpander handOffExpander = query -> {
        query.context().put(STANDALONE_KEY, "Books by George R.R. Martin cheaper than A Game of Thrones");
        return List.of(query.mutate()
                .text("Books by George R.R. Martin cheaper than A Game of Thrones")
                .context(new HashMap<>(query.context()))
                .build());
    };

    private RetrievalAugmentationAdvisor advisor(QueryExpander expander) {
        return RetrievalAugmentationAdvisor.builder()
                .queryExpander(expander)
                .documentRetriever(recordingRetriever)
                .documentPostProcessors((query, documents) -> {
                    postProcessed.set(query);
                    return documents;
                })
                .queryAugmenter(ContextualQueryAugmenter.builder().allowEmptyContext(true).build())
                .build();
    }

    private static ChatClientRequest twoTurnRequest() {
        List<Message> messages = List.of(
                new UserMessage("Recommend an epic fantasy series with political intrigue."),
                new AssistantMessage("Try A Game of Thrones by George R.R. Martin."),
                new UserMessage("Anything cheaper by the same author?"));
        return ChatClientRequest.builder()
                .prompt(new Prompt(messages))
                .context(Map.of(ChatMemory.CONVERSATION_ID, "conversation-1"))
                .build();
    }

    @Test
    void originalQueryCarriesTheWholePromptAsHistoryAndTheRequestContext() {
        advisor(handOffExpander).before(twoTurnRequest(), null);

        Query original = postProcessed.get();
        assertThat(original.text()).isEqualTo("Anything cheaper by the same author?");
        // history = prompt.getInstructions(): earlier turns plus the current user message, last.
        assertThat(original.history()).extracting(Message::getText).containsExactly(
                "Recommend an epic fantasy series with political intrigue.",
                "Try A Game of Thrones by George R.R. Martin.",
                "Anything cheaper by the same author?");
        assertThat(original.context()).containsEntry(ChatMemory.CONVERSATION_ID, "conversation-1");
    }

    @Test
    void postProcessorsReceiveTheOriginalQueryNotTheExpandedOne() {
        advisor(handOffExpander).before(twoTurnRequest(), null);

        assertThat(retrieved.get().text()).isEqualTo("Books by George R.R. Martin cheaper than A Game of Thrones");
        // This is the bug #36 fixes: the reranker judges against the context-free follow-up.
        assertThat(postProcessed.get().text()).isEqualTo("Anything cheaper by the same author?");
    }

    @Test
    void anExpanderCanHandDataToPostProcessorsThroughTheOriginalQueryContext() {
        ChatClientRequest augmented = advisor(handOffExpander).before(twoTurnRequest(), null);

        // The original query's context is the advisor's own mutable map: the expander's write is
        // visible to post-processors, which run on the caller thread after every retrieval joins.
        assertThat(postProcessed.get().context())
                .containsEntry(STANDALONE_KEY, "Books by George R.R. Martin cheaper than A Game of Thrones");
        // ...and it is the same map that becomes the response context.
        assertThat(augmented.context())
                .containsEntry(STANDALONE_KEY, "Books by George R.R. Martin cheaper than A Game of Thrones")
                .containsKey(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
    }

    @Test
    void retrievalRunsOnTheAdvisorsOwnPoolWhenNoExecutorIsGiven() {
        advisor(handOffExpander).before(twoTurnRequest(), null);

        assertThat(retrieverThreads).isNotEmpty().allMatch(name -> name.startsWith("ai-advisor-"));
    }

    @Test
    void equalExpandedQueriesCrashTheAdvisor() {
        // Collectors.toMap over Map<Query, ...>: two equal queries are a duplicate key. An expander
        // must therefore de-duplicate its output, or a model that echoes the original as a
        // "variant" turns into a 5xx.
        QueryExpander echoing = query -> List.of(query, query.mutate().build());

        assertThatThrownBy(() -> advisor(echoing).before(twoTurnRequest(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate key");
    }
}
