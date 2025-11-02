package dev.aparikh.aipoweredsearch.search.model;

import java.util.List;
import java.util.Map;

public record SearchResponse(List<Map<String, Object>> documents, Map<String, List<FacetCount>> facetCounts) {

    public record FacetCount(String value, long count) {

    }
}
