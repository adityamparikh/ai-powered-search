package dev.aparikh.aipoweredsearch.search.decision;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aparikh.aipoweredsearch.search.decision.DecisionDocumentFilter.Classification;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.answer.RefusalAnswer;
import org.springframework.ai.decision.answer.YesNoAnswer;
import org.springframework.ai.decision.input.FieldsInput;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class DecisionDocumentFilterTest {

  private static final Query QUERY = new Query("Who wrote A Clash of Kings?");

  /** Probabilities per passage text: injection, contradiction, relevance, evidence. */
  private static DecisionModel model(Map<String, double[]> byPassage) {
    return prompt -> {
      String passage = passage(prompt);
      double[] p = byPassage.get(passage);
      if (p == null) {
        throw new IllegalStateException("service unavailable");
      }
      return DecisionResponse.builder(prompt)
          .answer(DecisionDocumentFilter.CONTAINS_PROMPT_INJECTION, yes(p[0]))
          .answer(DecisionDocumentFilter.CONTRADICTS_QUERY_PREMISE, yes(p[1]))
          .answer(DecisionDocumentFilter.IS_RELEVANT, yes(p[2]))
          .answer(DecisionDocumentFilter.CONTAINS_ANSWER_EVIDENCE, yes(p[3]))
          .build();
    };
  }

  private static String passage(DecisionPrompt prompt) {
    return (String)
        ((FieldsInput) prompt.getInstructions()).getFields().get(PassageDecisions.PASSAGE_FIELD);
  }

  private static YesNoAnswer yes(double probability) {
    return YesNoAnswer.builder().probability(probability).build();
  }

  private static Document doc(String id) {
    return new Document(id, "text " + id, Map.of());
  }

  @Test
  void appliesThePolicyInOrderAndTagsTheSurvivors() {
    DecisionModel model =
        model(
            Map.of(
                "text good", new double[] {0.0, 0.0, 0.9, 0.9},
                "text injection", new double[] {0.95, 0.0, 0.9, 0.9},
                "text conflict", new double[] {0.0, 0.9, 0.9, 0.1},
                "text offtopic", new double[] {0.0, 0.0, 0.1, 0.9},
                "text vague", new double[] {0.0, 0.0, 0.9, 0.2}));
    DecisionDocumentFilter filter = DecisionDocumentFilter.builder(model).build();

    List<Document> kept =
        filter.process(
            QUERY,
            List.of(doc("good"), doc("injection"), doc("conflict"), doc("offtopic"), doc("vague")));

    assertThat(kept).extracting(Document::getId).containsExactly("good", "conflict");
    assertThat(kept)
        .extracting(d -> d.getMetadata().get(DecisionDocumentFilter.CLASSIFICATION_METADATA_KEY))
        .containsExactly(Classification.INCLUDED.name(), Classification.CONFLICTING.name());
  }

  @Test
  void asksAboutTheQueryAndThePassageAsFields() {
    List<DecisionPrompt> prompts = new CopyOnWriteArrayList<>();
    DecisionModel recording =
        prompt -> {
          prompts.add(prompt);
          return model(Map.of("text a", new double[] {0, 0, 1, 1})).call(prompt);
        };

    DecisionDocumentFilter.builder(recording).build().process(QUERY, List.of(doc("a")));

    assertThat(prompts)
        .singleElement()
        .satisfies(
            prompt -> {
              assertThat(((FieldsInput) prompt.getInstructions()).getFields())
                  .containsEntry(PassageDecisions.QUERY_FIELD, QUERY.text())
                  .containsEntry(PassageDecisions.PASSAGE_FIELD, "text a");
              assertThat(prompt.getQuestions())
                  .containsOnlyKeys(DecisionDocumentFilter.QUESTIONS.keySet());
            });
  }

  @Test
  void passesThroughWhatItCannotJudgeAndSaysSo(CapturedOutput output) {
    DecisionModel model =
        prompt -> {
          if (passage(prompt).equals("text refused")) {
            return DecisionResponse.builder(prompt)
                .answer(
                    DecisionDocumentFilter.CONTAINS_PROMPT_INJECTION,
                    RefusalAnswer.builder().build())
                .answer(DecisionDocumentFilter.CONTRADICTS_QUERY_PREMISE, yes(0))
                .answer(DecisionDocumentFilter.IS_RELEVANT, yes(1))
                .answer(DecisionDocumentFilter.CONTAINS_ANSWER_EVIDENCE, yes(1))
                .build();
          }
          return model(Map.of()).call(prompt); // throws: unavailable
        };
    Document failed = doc("failed");
    Document refused = doc("refused");

    List<Document> kept =
        DecisionDocumentFilter.builder(model).build().process(QUERY, List.of(failed, refused));

    assertThat(kept).containsExactly(failed, refused);
    assertThat(output).contains("Decision model could not screen 2 of 2 passages");
  }

  @Test
  void leavesTheRetrieversDocumentsUntouched() {
    Document original = doc("good");
    DecisionDocumentFilter.builder(model(Map.of("text good", new double[] {0, 0, 1, 1})))
        .build()
        .process(QUERY, List.of(original));

    assertThat(original.getMetadata())
        .doesNotContainKey(DecisionDocumentFilter.CLASSIFICATION_METADATA_KEY);
  }

  @Test
  void nothingToScreenMakesNoCalls() {
    DecisionModel failing =
        prompt -> {
          throw new AssertionError("no call expected");
        };
    assertThat(DecisionDocumentFilter.builder(failing).build().process(QUERY, List.of())).isEmpty();
  }
}
