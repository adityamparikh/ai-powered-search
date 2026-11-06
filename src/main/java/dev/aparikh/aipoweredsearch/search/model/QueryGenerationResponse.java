package dev.aparikh.aipoweredsearch.search.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record QueryGenerationResponse(
    String q,
    @Nullable List<String> fq,
    @Nullable String sort,
    @Nullable String fl,
    @JsonProperty("facet.fields") @Nullable List<String> facetFields,
    @JsonProperty("facet.query") @Nullable String facetQuery) {}
