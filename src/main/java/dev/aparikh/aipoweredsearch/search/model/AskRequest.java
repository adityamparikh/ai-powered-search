package dev.aparikh.aipoweredsearch.search.model;

import org.jspecify.annotations.Nullable;

/**
 * Request model for conversational question-answering with RAG.
 *
 * @param question the natural language question to ask
 * @param conversationId the ID of the conversation to continue, as returned by an earlier {@code
 *     /ask}; null starts a new conversation. An ID the server did not issue is rejected.
 */
public record AskRequest(String question, @Nullable String conversationId) {
  /**
   * Creates an AskRequest that starts a new conversation.
   *
   * @param question the question to ask
   */
  public AskRequest(String question) {
    this(question, null);
  }
}
