package dev.aparikh.aipoweredsearch.search.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record QueryGenerationResponse(
        String q,
        List<String> fq,
        String sort,
        @JsonProperty("facet.fields") List<String> facetFields,
        @JsonProperty("facet.query") String facetQuery
) {
}