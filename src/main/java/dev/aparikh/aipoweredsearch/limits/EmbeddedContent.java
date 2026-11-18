package dev.aparikh.aipoweredsearch.limits;

import org.jspecify.annotations.Nullable;

/**
 * A request element whose content is embedded, so that {@link InputLimit#BATCH_CONTENT_LENGTH} can
 * sum it over a batch without depending on the indexing model.
 */
public interface EmbeddedContent {

  /**
   * Returns the text that is embedded.
   *
   * @return the content, or {@code null} if the request left it out
   */
  @Nullable String content();
}
