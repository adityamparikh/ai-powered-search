package dev.aparikh.aipoweredsearch.solr;

import org.apache.solr.common.SolrException;
import org.jspecify.annotations.Nullable;

/**
 * Describes a failed Solr call without its message, for logs at INFO and above.
 *
 * <p>Solr's error messages quote what they failed on. A query or filter that does not parse comes
 * back as {@code Cannot parse 'author:"Jane Doe" AND (...'}. A document that does not index comes
 * back as {@code [doc=d1] Error adding field 'price'='<field value>'}, and field values are
 * passages. SolrJ puts that text in the exception message. So the application logs the exception
 * type and Solr's HTTP error code at WARN or ERROR, and the full exception at DEBUG.
 */
public final class SolrErrors {

  private static final int MAX_CAUSE_DEPTH = 10;

  private SolrErrors() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Returns the exception's type and, when a {@link SolrException} is in its cause chain, that
   * exception's type and HTTP error code. For example: {@code RuntimeException caused by
   * RemoteSolrException, Solr code 400}. Never includes a message.
   */
  public static String describe(Throwable error) {
    StringBuilder description = new StringBuilder(error.getClass().getSimpleName());
    @Nullable Throwable current = error;
    for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (current instanceof SolrException solrException) {
        if (current != error) {
          description.append(" caused by ").append(current.getClass().getSimpleName());
        }
        return description.append(", Solr code ").append(solrException.code()).toString();
      }
      current = current.getCause() == current ? null : current.getCause();
    }
    return description.toString();
  }
}
