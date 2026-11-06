package dev.aparikh.aipoweredsearch.search.model;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record SearchResponse(
    List<Map<String, Object>> documents,
    Map<String, List<FacetCount>> facetCounts,
    Map<String, List<String>> highlighting,
    @Nullable SpellCheckSuggestion spellCheckSuggestion) {

  public record FacetCount(String value, long count) {}

  public record SpellCheckSuggestion(String suggestion, String originalQuery) {}

  // Backwards compatibility constructor for existing code
  public SearchResponse(
      List<Map<String, Object>> documents, Map<String, List<FacetCount>> facetCounts) {
    this(documents, facetCounts, Map.of(), null);
  }
}
