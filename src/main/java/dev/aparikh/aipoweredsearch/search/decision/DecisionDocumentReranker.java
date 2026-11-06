package dev.aparikh.aipoweredsearch.search.decision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.question.YesNoQuestion;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.util.Assert;

/**
 * Reorders retrieved passages by asking a {@link DecisionModel}, one passage at a time, how likely
 * each is to answer the query, keeps the {@code topK} most likely, and drops any below {@code
 * minimumScore}.
 *
 * <p>Retrieval ranks by keyword and embedding proximity, which finds candidates well and orders
 * them poorly: a passage can be about the right subject without containing the answer. The question
 * asked here ({@value #QUESTION_NAME}) measures exactly that, and its yes-probability is the score.
 *
 * <p>Provider-agnostic: any {@link DecisionModel} answers, for example Spring AI's {@code
 * SystemOneDecisionModel} (TypeSafe's Jev).
 *
 * <p>Each scored document carries its score under {@value #SCORE_METADATA_KEY}. A passage the model
 * could not score is kept, since a failed call is not evidence about relevance, but it goes after
 * every scored passage, so under {@code topK} a failure never evicts a known-good one. When every
 * call fails this is the retriever's order again, with a WARN.
 */
public class DecisionDocumentReranker implements DocumentPostProcessor {

  private static final Logger log = LoggerFactory.getLogger(DecisionDocumentReranker.class);

  /** How many passages are judged at once unless configured otherwise. */
  public static final int DEFAULT_MAX_CONCURRENCY = 8;

  /** Metadata key holding the score this reranker gave a document. */
  public static final String SCORE_METADATA_KEY = "decision.rerank.score";

  /** The name of the question asked about each passage. */
  public static final String QUESTION_NAME = "answers_query";

  static final YesNoQuestion DEFAULT_QUESTION =
      YesNoQuestion.builder()
          .text(
              "Could the `passage` answer the `query`? Judge whether the passage states information"
                  + " that answers the query, not merely whether it covers the same subject.")
          .whenYes("The passage contains information that answers the query")
          .whenNo("The passage is about something else, or mentions the subject without answering")
          .build();

  private final PassageDecisions decisions;
  private final @Nullable Integer topK;
  private final double minimumScore;

  private DecisionDocumentReranker(
      DecisionModel decisionModel,
      YesNoQuestion question,
      @Nullable Integer topK,
      double minimumScore,
      int maxConcurrency) {
    this.decisions =
        new PassageDecisions(decisionModel, Map.of(QUESTION_NAME, question), maxConcurrency);
    this.topK = topK;
    this.minimumScore = minimumScore;
  }

  public static Builder builder(DecisionModel decisionModel) {
    return new Builder(decisionModel);
  }

  @Override
  public List<Document> process(Query query, List<Document> documents) {
    Assert.notNull(query, "query must not be null");
    Assert.notNull(documents, "documents must not be null");
    if (documents.isEmpty()) {
      return List.of();
    }

    PassageDecisions.Outcome outcome = this.decisions.ask(query.text(), documents);
    List<Scored> scored = new ArrayList<>(documents.size());
    List<Document> unscored = new ArrayList<>();
    RuntimeException firstFailure = outcome.failure();
    for (int i = 0; i < documents.size(); i++) {
      Document document = documents.get(i);
      Double score = null;
      DecisionResponse response = outcome.responses().get(i);
      if (response != null) {
        try {
          score = response.getYesNo(QUESTION_NAME).getProbability();
        } catch (RuntimeException ex) {
          // A refusal or a missing answer must not fail the whole request.
          if (firstFailure == null) {
            firstFailure = ex;
          }
        }
      }
      if (score == null) {
        unscored.add(document);
      } else if (score >= this.minimumScore) {
        scored.add(new Scored(i, document, score));
      }
    }
    if (!unscored.isEmpty()) {
      log.warn(
          "Decision model could not score {} of {} passages, keeping them unscored after the"
              + " scored ones: {}",
          unscored.size(),
          documents.size(),
          firstFailure == null ? "unknown failure" : firstFailure.getMessage());
    }

    List<Document> reordered = new ArrayList<>(scored.size() + unscored.size());
    scored.stream()
        .sorted(
            Comparator.comparingDouble(Scored::score)
                .reversed()
                .thenComparingInt(Scored::originalIndex))
        .map(
            entry ->
                PassageDecisions.withMetadata(entry.document(), SCORE_METADATA_KEY, entry.score()))
        .forEach(reordered::add);
    reordered.addAll(unscored);

    int limit = this.topK == null ? reordered.size() : Math.min(this.topK, reordered.size());
    return List.copyOf(reordered.subList(0, limit));
  }

  private record Scored(int originalIndex, Document document, double score) {}

  @Override
  public String toString() {
    return String.format(
        "DecisionDocumentReranker{topK=%s, minimumScore=%s}", this.topK, this.minimumScore);
  }

  /** Builder for {@link DecisionDocumentReranker}. */
  public static final class Builder {

    private final DecisionModel decisionModel;
    private YesNoQuestion question = DEFAULT_QUESTION;
    private @Nullable Integer topK;
    private double minimumScore;
    private int maxConcurrency = DEFAULT_MAX_CONCURRENCY;

    private Builder(DecisionModel decisionModel) {
      Assert.notNull(decisionModel, "decisionModel must not be null");
      this.decisionModel = decisionModel;
    }

    /**
     * Replaces the relevance question. Write it about {@code query} and {@code passage}, the two
     * fields of the decision input.
     *
     * @param question the question
     * @return this builder
     */
    public Builder question(YesNoQuestion question) {
      Assert.notNull(question, "question must not be null");
      this.question = question;
      return this;
    }

    /**
     * @param topK how many documents to keep after reordering
     * @return this builder
     */
    public Builder topK(int topK) {
      Assert.isTrue(topK >= 1, "topK must be at least 1");
      this.topK = topK;
      return this;
    }

    /**
     * Drops passages scoring below a bar. At zero, nothing is dropped and this only reorders.
     *
     * @param minimumScore the lowest score worth keeping
     * @return this builder
     */
    public Builder minimumScore(double minimumScore) {
      Assert.isTrue(
          minimumScore >= 0.0d && minimumScore <= 1.0d, "minimumScore must be between 0 and 1");
      this.minimumScore = minimumScore;
      return this;
    }

    /**
     * @param maxConcurrency how many passages are judged at once
     * @return this builder
     */
    public Builder maxConcurrency(int maxConcurrency) {
      this.maxConcurrency = maxConcurrency;
      return this;
    }

    public DecisionDocumentReranker build() {
      return new DecisionDocumentReranker(
          this.decisionModel, this.question, this.topK, this.minimumScore, this.maxConcurrency);
    }
  }
}
