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
 * Remembers, per conversation, the fused candidates (the joiner's output, before any
 * post-processor screens or reranks them) and what the reranker was handed.
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
    private final Map<String, List<String>> lastJoinedByConversation = new ConcurrentHashMap<>();

    /** Records the joiner's output: the candidates after fusion. */
    public void recordJoined(Map<Query, List<List<Document>>> documentsForQuery, List<Document> joined) {
        documentsForQuery.keySet().stream()
                .map(query -> query.context().get(ChatMemory.CONVERSATION_ID))
                .filter(Objects::nonNull)
                .findFirst()
                .ifPresent(conversationId -> lastJoinedByConversation.put(conversationId.toString(),
                        joined.stream().map(Document::getId).filter(Objects::nonNull).toList()));
    }

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

    /**
     * The latest turn's fused candidates: the joiner's output when it was recorded, otherwise what
     * the reranker was handed.
     */
    public List<String> lastCandidates(String conversationId) {
        List<String> joined = lastJoinedByConversation.get(conversationId);
        if (joined != null) {
            return joined;
        }
        return last(conversationId).map(Capture::candidateIds).orElse(List.of());
    }
}
