package dev.aparikh.aipoweredsearch.search.model;

import dev.aparikh.aipoweredsearch.limits.InputLimit;
import dev.aparikh.aipoweredsearch.limits.WithinLimit;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;

/**
 * Request model for conversational question-answering with RAG.
 *
 * @param question the natural language question to ask, at most {@code
 *     search.limits.max-question-length} characters
 * @param conversationId optional conversation ID for maintaining context across requests; null
 *     means use default
 */
public record AskRequest(
    @NotBlank @WithinLimit(InputLimit.QUESTION_LENGTH) String question,
    @Nullable String conversationId) {
  /**
   * Creates an AskRequest with default conversation ID.
   *
   * @param question the question to ask
   */
  public AskRequest(String question) {
    this(question, "default");
  }
}
