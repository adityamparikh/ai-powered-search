package dev.aparikh.aipoweredsearch.search.model;

import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

public record SearchRequest(String query, List<String> filterQueries, String sort, String fieldList, Facet facet) {

    public boolean hasFacets() {
        return facet != null && !CollectionUtils.isEmpty(facet.fields());
    }

    public boolean hasSort() {
        return StringUtils.hasText(sort);
    }

    public boolean hasFieldList() {
        return StringUtils.hasText(fieldList);
    }

    public record Facet(List<String> fields, String query) {
    }
}
