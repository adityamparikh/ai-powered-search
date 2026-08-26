package dev.aparikh.aipoweredsearch.search.model;

import org.jspecify.annotations.Nullable;

/**
 * Request model for conversational question-answering with RAG.
 *
 * @param question the natural language question to ask
 * @param conversationId optional conversation ID for maintaining context across requests; null means use default
 */
public record AskRequest(
        String question,
        @Nullable String conversationId
) {
    /**
     * Creates an AskRequest with default conversation ID.
     *
     * @param question the question to ask
     */
    public AskRequest(String question) {
        this(question, "default");
    }
}
