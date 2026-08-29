package dev.aparikh.aipoweredsearch.search;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reorders retrieved documents by asking an LLM to judge them against the question, then
 * keeps only the best few.
 *
 * <p>Retrieval decides which documents are <em>candidates</em>; reranking decides which ones
 * actually reach the prompt. Hybrid search fuses two rank orders that know nothing about the
 * question's intent — BM25 sees term overlap, vector search sees embedding proximity. Neither
 * can tell that a chunk merely <em>mentions</em> the right words. A model reading the question
 * and the candidates side by side can.</p>
 *
 * <p>The payoff comes from <strong>discarding</strong>, not merely reordering: fewer, more
 * relevant chunks mean less irrelevant context competing for the model's attention. That only
 * works if retrieval hands over more documents than {@code topK} — reranking N documents down
 * to N reorders them but throws nothing away. See {@code search.rag.hybrid.top-k}.</p>
 *
 * <p><strong>Cost.</strong> This adds a second model call to every RAG turn, carrying the full
 * text of every candidate. The prompt is unique per question, so it does not benefit from
 * Anthropic prompt caching. Disable with {@code search.rag.rerank.enabled=false}.</p>
 *
 * <p><strong>Failure is never fatal.</strong> Reranking is an optimisation layered on top of a
 * working retriever, so anything that goes wrong — an unavailable model, a malformed ranking,
 * indexes pointing nowhere — degrades to the retriever's own RRF order rather than failing the
 * request.</p>
 *
 * @see HybridDocumentRetriever
 */
public class RerankingDocumentPostProcessor implements DocumentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(RerankingDocumentPostProcessor.class);

    /**
     * The model's verdict: original document indexes, most relevant first.
     *
     * @param documentIndexes zero-based positions into the list handed to the model
     */
    public record Ranking(@Nullable List<Integer> documentIndexes) {
    }

    private final ChatClient chatClient;
    private final int topK;

    /**
     * @param chatClient the client used to ask for a ranking
     * @param topK       how many documents survive reranking; must be positive
     */
    public RerankingDocumentPostProcessor(ChatClient chatClient, int topK) {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive, got: " + topK);
        }
        this.chatClient = chatClient;
        this.topK = topK;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            // Nothing to judge — don't spend a model call saying so.
            return documents == null ? List.of() : documents;
        }

        try {
            Ranking ranking = chatClient.prompt()
                    .user(rankingPrompt(query, documents))
                    .call()
                    .entity(Ranking.class);

            List<Document> reranked = select(ranking, documents);
            if (reranked.isEmpty()) {
                log.warn("Reranking produced no usable indexes; keeping retrieval order");
                return truncate(documents);
            }

            log.debug("Reranked {} candidates down to {}", documents.size(), reranked.size());
            return reranked;
        } catch (Exception e) {
            log.warn("Reranking failed, keeping retrieval order: {}", e.getMessage());
            return truncate(documents);
        }
    }

    /**
     * Maps model-supplied indexes back to documents, discarding anything unusable.
     *
     * <p>The indexes come from a language model, so they are not trusted: an out-of-range
     * value would throw from {@link List#get}, and a repeated one would place the same chunk
     * in the context twice.</p>
     */
    private List<Document> select(@Nullable Ranking ranking, List<Document> documents) {
        if (ranking == null || ranking.documentIndexes() == null) {
            return List.of();
        }

        Set<Integer> seen = new LinkedHashSet<>();
        List<Document> selected = new ArrayList<>(topK);
        for (Integer index : ranking.documentIndexes()) {
            if (index == null || index < 0 || index >= documents.size() || !seen.add(index)) {
                continue;
            }
            selected.add(documents.get(index));
            if (selected.size() == topK) {
                break;
            }
        }
        return selected;
    }

    /**
     * Falls back to the order retrieval produced, still honouring topK.
     */
    private List<Document> truncate(List<Document> documents) {
        return documents.size() <= topK ? documents : List.copyOf(documents.subList(0, topK));
    }

    private String rankingPrompt(Query query, List<Document> documents) {
        StringBuilder prompt = new StringBuilder("""
                Rank the following documents by their relevance to the query.
                Return the document indexes in order from most relevant to least relevant,
                omitting any document that is not relevant to the query.

                Query:
                """);
        prompt.append(query.text()).append("\n\nDocuments:\n");
        for (int i = 0; i < documents.size(); i++) {
            prompt.append("\n[").append(i).append("]\n")
                    .append(documents.get(i).getText()).append("\n");
        }
        return prompt.toString();
    }
}
