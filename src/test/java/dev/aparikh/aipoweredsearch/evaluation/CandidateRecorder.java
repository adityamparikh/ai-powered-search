package dev.aparikh.aipoweredsearch.evaluation;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the candidate documents handed to the first post-processor, i.e. the fused
 * retrieval output before reranking trims it, keyed by conversation id.
 *
 * <p>{@code RetrievalAugmentationAdvisor} builds {@code Query.context()} from the request
 * context, which carries the {@link ChatMemory#CONVERSATION_ID} advisor parameter. That is
 * how a capture is tied back to the evaluation case that produced it.</p>
 *
 * <p>Only the latest capture per conversation is kept, so this assumes cases run one at a time,
 * each on its own conversation id (a follow-up's standalone rewrite uses a
 * {@code -standalone} suffix). Concurrent cases sharing an id would overwrite each other.</p>
 */
public class CandidateRecorder {

    /**
     * @param queryText    the text of the query the post-processor received
     * @param candidateIds document ids in the order the post-processor received them
     */
    public record Capture(String queryText, List<String> candidateIds) {
    }

    private final Map<String, Capture> lastByConversation = new ConcurrentHashMap<>();

    public void record(Query query, List<Document> documents) {
        Object conversationId = query.context().get(ChatMemory.CONVERSATION_ID);
        if (conversationId == null) {
            return;
        }
        List<String> ids = documents.stream().map(Document::getId).filter(Objects::nonNull).toList();
        lastByConversation.put(conversationId.toString(), new Capture(query.text(), ids));
    }

    /** The most recent capture for a conversation, i.e. its latest turn. */
    public Optional<Capture> last(String conversationId) {
        return Optional.ofNullable(lastByConversation.get(conversationId));
    }

    public List<String> lastCandidates(String conversationId) {
        return last(conversationId).map(Capture::candidateIds).orElse(List.of());
    }
}
