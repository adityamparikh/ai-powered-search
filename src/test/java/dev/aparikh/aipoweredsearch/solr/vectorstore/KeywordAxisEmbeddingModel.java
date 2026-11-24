package dev.aparikh.aipoweredsearch.solr.vectorstore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * A deterministic {@link EmbeddingModel} for keyless tests: each known keyword is one axis of a
 * 1536-dimensional space, and a text's vector is the sum of the axes of the keywords it contains.
 *
 * <p>Cosine similarity is then predictable by hand: a text shares a direction with a query exactly
 * when they share keywords. A text with no known keyword points along {@link #OTHER_AXIS}.
 */
final class KeywordAxisEmbeddingModel implements EmbeddingModel {

  static final int DIMENSIONS = 1536;
  static final int OTHER_AXIS = 100;

  private static final Map<String, Integer> AXES =
      Map.of("solr", 0, "spring", 1, "python", 2, "cooking", 3);

  /** Number of texts embedded so far, so tests can tell a batch call from per-document calls. */
  private int embeddedTexts;

  static float[] axis(int index) {
    float[] vector = new float[DIMENSIONS];
    vector[index] = 1f;
    return vector;
  }

  static float[] vectorFor(String text) {
    float[] vector = new float[DIMENSIONS];
    String lower = text.toLowerCase(Locale.ROOT);
    boolean matched = false;
    for (Map.Entry<String, Integer> entry : AXES.entrySet()) {
      if (lower.contains(entry.getKey())) {
        vector[entry.getValue()] = 1f;
        matched = true;
      }
    }
    if (!matched) {
      vector[OTHER_AXIS] = 1f;
    }
    return vector;
  }

  int embeddedTexts() {
    return embeddedTexts;
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    List<Embedding> embeddings = new ArrayList<>();
    List<String> texts = request.getInstructions();
    for (int i = 0; i < texts.size(); i++) {
      embeddings.add(new Embedding(vectorFor(texts.get(i)), i));
    }
    embeddedTexts += texts.size();
    return new EmbeddingResponse(embeddings);
  }

  @Override
  public float[] embed(Document document) {
    return vectorFor(document.getText() == null ? "" : document.getText());
  }

  @Override
  public int dimensions() {
    return DIMENSIONS;
  }
}
