package dev.aparikh.aipoweredsearch.search.decision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.question.DecisionQuestion;
import org.springframework.ai.document.Document;
import org.springframework.util.Assert;

/**
 * Asks the same questions about each passage of a retrieval, one {@link DecisionModel} call per
 * passage, fanned out concurrently on virtual threads.
 *
 * <p>The input is a {@link org.springframework.ai.decision.input.FieldsInput} with two fields,
 * {@value #QUERY_FIELD} and {@value #PASSAGE_FIELD}, so questions refer to them by name ("Does the
 * `passage` answer the `query`?").
 *
 * <p>A call that fails yields no response rather than an exception: a passage that could not be
 * judged is not thereby irrelevant or dangerous, and the caller decides what to do with it.
 */
final class PassageDecisions {

  /** The query field of the decision input. */
  static final String QUERY_FIELD = "query";

  /** The passage field of the decision input. */
  static final String PASSAGE_FIELD = "passage";

  /** The outcome of asking about every passage, in the order the passages were given. */
  record Outcome(List<@Nullable DecisionResponse> responses, @Nullable RuntimeException failure) {}

  private final DecisionModel decisionModel;
  private final Map<String, DecisionQuestion> questions;
  private final int maxConcurrency;

  PassageDecisions(
      DecisionModel decisionModel,
      Map<String, ? extends DecisionQuestion> questions,
      int maxConcurrency) {
    Assert.notNull(decisionModel, "decisionModel must not be null");
    Assert.notEmpty(questions, "questions must not be empty");
    Assert.isTrue(maxConcurrency >= 1, "maxConcurrency must be at least 1");
    this.decisionModel = decisionModel;
    // Kept in the order given: a provider may answer them in that order.
    this.questions = new LinkedHashMap<>(questions);
    this.maxConcurrency = maxConcurrency;
  }

  /**
   * Asks the questions about each document.
   *
   * @param query the text the passages are judged against
   * @param documents the passages
   * @return one response per document, {@code null} where the call failed, and the first failure
   */
  Outcome ask(String query, List<Document> documents) {
    Semaphore permits = new Semaphore(this.maxConcurrency);
    List<Future<DecisionResponse>> futures = new ArrayList<>(documents.size());
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (Document document : documents) {
        DecisionPrompt prompt = prompt(query, document);
        futures.add(
            executor.submit(
                () -> {
                  permits.acquire();
                  try {
                    return this.decisionModel.call(prompt);
                  } finally {
                    permits.release();
                  }
                }));
      }
    }
    // The executor is closed, so every future is done.
    List<@Nullable DecisionResponse> responses = new ArrayList<>(futures.size());
    RuntimeException firstFailure = null;
    for (Future<DecisionResponse> future : futures) {
      DecisionResponse response = null;
      try {
        response = future.get();
      } catch (ExecutionException ex) {
        if (firstFailure == null) {
          firstFailure =
              ex.getCause() instanceof RuntimeException cause
                  ? cause
                  : new IllegalStateException(ex.getCause());
        }
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        if (firstFailure == null) {
          firstFailure = new IllegalStateException("interrupted while waiting for decisions", ex);
        }
      }
      responses.add(response);
    }
    return new Outcome(responses, firstFailure);
  }

  private DecisionPrompt prompt(String query, Document document) {
    Map<String, @Nullable Object> fields = new LinkedHashMap<>();
    fields.put(QUERY_FIELD, query);
    String text = document.getText();
    fields.put(PASSAGE_FIELD, text == null ? "" : text);
    return DecisionPrompt.builder().fields(fields).questions(this.questions).build();
  }

  /** A copy of the document with one more metadata entry, leaving the original untouched. */
  static Document withMetadata(Document document, String key, Object value) {
    // Rebuilt explicitly: Document.mutate() shares the original metadata map, so
    // metadata(key, value) would write back into the retriever's document.
    Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
    metadata.put(key, value);
    return document.mutate().metadata(metadata).build();
  }
}
