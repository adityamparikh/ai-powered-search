package dev.aparikh.aipoweredsearch.search.model;

import java.util.List;

/**
 * Response model for conversational question-answering with RAG.
 *
 * @param answer the generated answer from Claude with retrieved context
 * @param conversationId the conversation ID used for this request
 * @param sources the IDs of documents used as context for generating the answer
 */
public record AskResponse(
        String answer,
        String conversationId,
        List<String> sources
) {
}
