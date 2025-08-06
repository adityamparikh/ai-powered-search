package dev.aparikh.aipoweredsearch.search.model;

import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

public class SearchRequest {
    private final String query;
    private final List<String> filterQueries;
    private final String sort;
    private final Facet facet;


    public SearchRequest(String query, List<String> filterQueries, String sort, Facet facet) {
        this.query = query;
        this.filterQueries = filterQueries;
        this.sort = sort;
        this.facet = facet;
    }

    public String getQuery() {
        return query;
    }

    public List<String> getFilterQueries() {
        return filterQueries;
    }

    public String getSort() {
        return sort;
    }

    public Facet getFacet() {
        return facet;
    }

    public boolean hasFacets() {
        return facet != null && !CollectionUtils.isEmpty(facet.getFields());
    }

    public boolean hasSort() {
        return StringUtils.hasText(sort);
    }

    public static class Facet {
        private final List<String> fields;
        private final String query;

        public Facet(List<String> fields, String query) {
            this.fields = fields;
            this.query = query;
        }

        public List<String> getFields() {
            return fields;
        }

        public String getQuery() {
            return query;
        }
    }
}
