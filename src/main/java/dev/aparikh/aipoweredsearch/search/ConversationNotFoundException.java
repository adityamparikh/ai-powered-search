package dev.aparikh.aipoweredsearch.search;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Thrown when {@code /ask} receives a conversation ID that the server did not issue, i.e. one with
 * no history in chat memory. Rendered as a 404 problem detail.
 *
 * <p>The ID is deliberately left out of the problem detail: it is client input, and echoing it back
 * adds nothing the client does not already have.
 */
public class ConversationNotFoundException extends ErrorResponseException {

  public ConversationNotFoundException() {
    super(HttpStatus.NOT_FOUND, problemDetail(), null);
  }

  private static ProblemDetail problemDetail() {
    ProblemDetail problemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND,
            "No conversation with this ID. Omit conversationId to start a new conversation.");
    problemDetail.setTitle("Conversation not found");
    return problemDetail;
  }
}
