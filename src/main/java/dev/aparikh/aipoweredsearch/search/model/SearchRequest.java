package dev.aparikh.aipoweredsearch.search.model;

import org.jspecify.annotations.Nullable;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

public record SearchRequest(String query, @Nullable List<String> filterQueries, @Nullable String sort, @Nullable String fieldList, @Nullable Facet facet) {

    public boolean hasFacets() {
        return facet != null && !CollectionUtils.isEmpty(facet.fields());
    }

    public boolean hasSort() {
        return StringUtils.hasText(sort);
    }

    public boolean hasFieldList() {
        return StringUtils.hasText(fieldList);
    }

    public record Facet(@Nullable List<String> fields, @Nullable String query) {
    }
}
