package dev.aparikh.aipoweredsearch.search.model;

import java.util.List;
import java.util.Map;

public class SearchResponse {
    private final List<Map<String, Object>> documents;
    private final Map<String, List<FacetCount>> facetCounts;

    public SearchResponse(List<Map<String, Object>> documents, Map<String, List<FacetCount>> facetCounts) {
        this.documents = documents;
        this.facetCounts = facetCounts;
    }

    public List<Map<String, Object>> getDocuments() {
        return documents;
    }

    public Map<String, List<FacetCount>> getFacetCounts() {
        return facetCounts;
    }

    public static class FacetCount {
        private final String value;
        private final long count;

        public FacetCount(String value, long count) {
            this.value = value;
            this.count = count;
        }

        public String getValue() {
            return value;
        }

        public long getCount() {
            return count;
        }
    }
}
