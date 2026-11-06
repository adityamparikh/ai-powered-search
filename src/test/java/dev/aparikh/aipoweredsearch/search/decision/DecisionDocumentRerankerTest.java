package dev.aparikh.aipoweredsearch.search.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.answer.YesNoAnswer;
import org.springframework.ai.decision.input.FieldsInput;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class DecisionDocumentRerankerTest {

  private static final Query QUERY = new Query("Who wrote A Clash of Kings?");

  /** Scores per passage text; a passage without a score fails its call. */
  private static DecisionModel model(Map<String, Double> scores) {
    return prompt -> {
      Double score = scores.get(passage(prompt));
      if (score == null) {
        throw new IllegalStateException("service unavailable");
      }
      return DecisionResponse.builder(prompt)
          .answer(
              DecisionDocumentReranker.QUESTION_NAME,
              YesNoAnswer.builder().probability(score).build())
          .build();
    };
  }

  private static String passage(DecisionPrompt prompt) {
    return (String)
        ((FieldsInput) prompt.getInstructions()).getFields().get(PassageDecisions.PASSAGE_FIELD);
  }

  private static Document doc(String id) {
    return new Document(id, "text " + id, Map.of());
  }

  private static final List<Document> RETRIEVED = List.of(doc("a"), doc("b"), doc("c"), doc("d"));

  @Test
  void ordersByTheAnswerProbabilityAndKeepsTopK() {
    DecisionModel model = model(Map.of("text a", 0.1, "text b", 0.7, "text c", 0.9, "text d", 0.4));

    List<Document> reranked =
        DecisionDocumentReranker.builder(model).topK(3).build().process(QUERY, RETRIEVED);

    assertThat(reranked).extracting(Document::getId).containsExactly("c", "b", "d");
    assertThat(reranked)
        .extracting(d -> d.getMetadata().get(DecisionDocumentReranker.SCORE_METADATA_KEY))
        .containsExactly(0.9, 0.7, 0.4);
  }

  @Test
  void dropsPassagesBelowTheMinimumScore() {
    DecisionModel model = model(Map.of("text a", 0.1, "text b", 0.7, "text c", 0.9, "text d", 0.4));

    List<Document> reranked =
        DecisionDocumentReranker.builder(model).minimumScore(0.5).build().process(QUERY, RETRIEVED);

    assertThat(reranked).extracting(Document::getId).containsExactly("c", "b");
  }

  @Test
  void unscoredPassagesAreKeptAfterTheScoredOnes(CapturedOutput output) {
    DecisionModel model = model(Map.of("text c", 0.2, "text d", 0.8));

    List<Document> reranked =
        DecisionDocumentReranker.builder(model).topK(3).build().process(QUERY, RETRIEVED);

    // A failure never evicts a passage the model actually judged.
    assertThat(reranked).extracting(Document::getId).containsExactly("d", "c", "a");
    assertThat(output).contains("Decision model could not score 2 of 4 passages");
  }

  @Test
  void theMinimumScoreAppliesOnlyToPassagesTheModelScored() {
    // An unscored passage was never judged, so no bar can say it falls below it.
    DecisionModel model = model(Map.of("text a", 0.1, "text c", 0.9));

    List<Document> reranked =
        DecisionDocumentReranker.builder(model).minimumScore(0.5).build().process(QUERY, RETRIEVED);

    assertThat(reranked).extracting(Document::getId).containsExactly("c", "b", "d");
  }

  @Test
  void topKSmallerThanTheScoredPassagesLeavesTheUnscoredOnesOut() {
    DecisionModel model = model(Map.of("text a", 0.3, "text b", 0.6, "text d", 0.9));

    List<Document> reranked =
        DecisionDocumentReranker.builder(model).topK(2).build().process(QUERY, RETRIEVED);

    assertThat(reranked).extracting(Document::getId).containsExactly("d", "b");
  }

  @Test
  void aConcurrencyThatCouldNeverRunACallIsRejectedWithTheProperty() {
    DecisionModel model = model(Map.of());

    assertThatThrownBy(() -> DecisionDocumentReranker.builder(model).maxConcurrency(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("search.rag.decisions.max-concurrency");
    assertThatThrownBy(() -> DecisionDocumentFilter.builder(model).maxConcurrency(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("search.rag.decisions.max-concurrency");
  }

  @Test
  void everyCallFailingIsTheRetrievalOrder(CapturedOutput output) {
    List<Document> reranked =
        DecisionDocumentReranker.builder(model(Map.of())).build().process(QUERY, RETRIEVED);

    assertThat(reranked).containsExactlyElementsOf(RETRIEVED);
    assertThat(output).contains("Decision model could not score 4 of 4 passages");
  }

  @Test
  void neverRunsMoreCallsAtOnceThanAllowed() {
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    DecisionModel scores =
        model(Map.of("text a", 0.1, "text b", 0.2, "text c", 0.3, "text d", 0.4));
    DecisionModel slow =
        prompt -> {
          peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
          try {
            Thread.sleep(50);
            return scores.call(prompt);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
          } finally {
            inFlight.decrementAndGet();
          }
        };

    List<Document> reranked =
        DecisionDocumentReranker.builder(slow).maxConcurrency(2).build().process(QUERY, RETRIEVED);

    assertThat(reranked).extracting(Document::getId).containsExactly("d", "c", "b", "a");
    assertThat(peak.get()).isBetween(1, 2);
  }
}
