package dev.aparikh.aipoweredsearch.search.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Request model for conversational question-answering with RAG.
 *
 * <p>{@code question} must be non-blank: the RAG pipeline builds a {@code org.springframework.ai.rag.Query}
 * from it, and that type rejects blank text at construction. Validating here turns a blank question
 * into a 400 instead of letting it surface as an unhandled error from inside the advisor chain.</p>
 *
 * @param question the natural language question to ask
 * @param conversationId optional conversation ID for maintaining context across requests
 */
public record AskRequest(
        @NotBlank(message = "question must not be blank")
        String question,
        String conversationId
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
