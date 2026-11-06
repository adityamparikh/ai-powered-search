package dev.aparikh.aipoweredsearch.search.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Response model for conversational question-answering with RAG.
 *
 * @param answer the generated answer from Claude with retrieved context; may be null if the model
 *     returns no content
 * @param conversationId the conversation ID used for this request
 * @param sources the IDs of documents used as context for generating the answer
 */
public record AskResponse(@Nullable String answer, String conversationId, List<String> sources) {}
