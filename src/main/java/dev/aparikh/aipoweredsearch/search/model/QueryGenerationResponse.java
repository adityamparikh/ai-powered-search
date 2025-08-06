package dev.aparikh.aipoweredsearch.search.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class QueryGenerationResponse {
    private final String q;
    private final List<String> fq;
    private final String sort;
    @JsonProperty("facet.fields")
    private final List<String> facetFields;
    @JsonProperty("facet.query")
    private final String facetQuery;

    public QueryGenerationResponse(String q, List<String> fq, String sort, List<String> facetFields, String facetQuery) {
        this.q = q;
        this.fq = fq;
        this.sort = sort;
        this.facetFields = facetFields;
        this.facetQuery = facetQuery;
    }

    public String getQ() {
        return q;
    }

    public List<String> getFq() {
        return fq;
    }

    public String getSort() {
        return sort;
    }

    public List<String> getFacetFields() {
        return facetFields;
    }

    public String getFacetQuery() {
        return facetQuery;
    }
}