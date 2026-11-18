package dev.aparikh.aipoweredsearch.limits;

import java.util.Collection;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The configurable input limits, each tied to its {@code search.limits.*} property and to how the
 * value it bounds is measured.
 */
public enum InputLimit {
  /** Largest {@code k} (topK) for semantic and hybrid search. */
  K("search.limits.max-k", Measure.VALUE),
  /** Longest search query. */
  QUERY_LENGTH("search.limits.max-query-length", Measure.LENGTH),
  /** Longest {@code /ask} question. */
  QUESTION_LENGTH("search.limits.max-question-length", Measure.LENGTH),
  /** Most documents in one batch indexing request. */
  BATCH_SIZE("search.limits.max-batch-size", Measure.SIZE),
  /** Most content, summed over the {@link EmbeddedContent} elements of one batch. */
  BATCH_CONTENT_LENGTH("search.limits.max-batch-content-length", Measure.TOTAL_CONTENT_LENGTH),
  /** Longest content of one document. */
  DOCUMENT_LENGTH("search.limits.max-document-length", Measure.LENGTH),
  /** Longest document id. */
  ID_LENGTH("search.limits.max-id-length", Measure.LENGTH),
  /** Largest document metadata, nested keys and values included. */
  METADATA_LENGTH("search.limits.max-metadata-length", Measure.DEEP_LENGTH);

  private final String property;
  private final Measure measure;

  InputLimit(String property, Measure measure) {
    this.property = property;
    this.measure = measure;
  }

  /**
   * Returns the configuration property that sets this limit.
   *
   * @return the property name, such as {@code search.limits.max-k}
   */
  public String property() {
    return property;
  }

  /**
   * Returns the configured maximum.
   *
   * @param limits the bound limits
   * @return the largest accepted value, inclusive
   */
  public int max(InputLimits limits) {
    return switch (this) {
      case K -> limits.maxK();
      case QUERY_LENGTH -> limits.maxQueryLength();
      case QUESTION_LENGTH -> limits.maxQuestionLength();
      case BATCH_SIZE -> limits.maxBatchSize();
      case BATCH_CONTENT_LENGTH -> limits.maxBatchContentLength();
      case DOCUMENT_LENGTH -> limits.maxDocumentLength();
      case ID_LENGTH -> limits.maxIdLength();
      case METADATA_LENGTH -> limits.maxMetadataLength();
    };
  }

  /**
   * Measures a non-null value the way this limit counts it.
   *
   * @param value the value to measure
   * @return its size, comparable with {@link #max(InputLimits)}
   * @throws IllegalArgumentException if the value's type cannot be measured by this limit
   */
  long measure(Object value) {
    return switch (measure) {
      case VALUE -> {
        if (value instanceof Number number) {
          yield number.longValue();
        }
        throw unsupported(value);
      }
      case LENGTH -> {
        if (value instanceof CharSequence text) {
          yield codePoints(text);
        }
        throw unsupported(value);
      }
      case SIZE -> {
        if (value instanceof Collection<?> collection) {
          yield collection.size();
        }
        throw unsupported(value);
      }
      case TOTAL_CONTENT_LENGTH -> {
        if (value instanceof Collection<?> collection) {
          long total = 0;
          for (Object element : collection) {
            String content =
                element instanceof EmbeddedContent embedded ? embedded.content() : null;
            if (content != null) {
              total += codePoints(content);
            }
          }
          yield total;
        }
        throw unsupported(value);
      }
      case DEEP_LENGTH -> deepLength(value);
    };
  }

  /**
   * Describes the maximum for an error message.
   *
   * @param limits the bound limits
   * @return for example {@code "must be at most 1000 characters"}
   */
  String describe(InputLimits limits) {
    return "must be at most " + max(limits) + measure.unit;
  }

  private IllegalArgumentException unsupported(Object value) {
    return new IllegalArgumentException(
        "@WithinLimit(" + name() + ") does not support " + value.getClass().getName());
  }

  private static long codePoints(CharSequence text) {
    return Character.codePointCount(text, 0, text.length());
  }

  /**
   * Code points of every key and scalar, recursively. Each map entry, collection element and
   * container counts at least one, so that {@code [[],[],...]} or a map of nulls is still bounded.
   */
  private static long deepLength(@Nullable Object value) {
    return switch (value) {
      case null -> 1;
      case CharSequence text -> Math.max(1, codePoints(text));
      case Map<?, ?> map -> {
        long total = 1;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          total += deepLength(entry.getKey()) + deepLength(entry.getValue());
        }
        yield total;
      }
      case Collection<?> collection -> {
        long total = 1;
        for (Object element : collection) {
          total += deepLength(element);
        }
        yield total;
      }
      default -> Math.max(1, codePoints(String.valueOf(value)));
    };
  }

  private enum Measure {
    VALUE(""),
    LENGTH(" characters"),
    SIZE(" documents"),
    TOTAL_CONTENT_LENGTH(" characters in total"),
    DEEP_LENGTH(" characters");

    private final String unit;

    Measure(String unit) {
      this.unit = unit;
    }
  }
}
