package dev.aparikh.aipoweredsearch.limits;

/** The configurable input limits, each tied to its {@code search.limits.*} property. */
public enum InputLimit {
  /** Largest {@code k} (topK) for semantic and hybrid search. */
  K("search.limits.max-k", ""),
  /** Longest search query. */
  QUERY_LENGTH("search.limits.max-query-length", " characters"),
  /** Longest {@code /ask} question. */
  QUESTION_LENGTH("search.limits.max-question-length", " characters"),
  /** Most documents in one batch indexing request. */
  BATCH_SIZE("search.limits.max-batch-size", " documents"),
  /** Longest document content to index. */
  DOCUMENT_LENGTH("search.limits.max-document-length", " characters");

  private final String property;
  private final String unit;

  InputLimit(String property, String unit) {
    this.property = property;
    this.unit = unit;
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
      case DOCUMENT_LENGTH -> limits.maxDocumentLength();
    };
  }

  /**
   * Describes the maximum for an error message.
   *
   * @param limits the bound limits
   * @return for example {@code "must be at most 1000 characters"}
   */
  String describe(InputLimits limits) {
    return "must be at most " + max(limits) + unit;
  }
}
