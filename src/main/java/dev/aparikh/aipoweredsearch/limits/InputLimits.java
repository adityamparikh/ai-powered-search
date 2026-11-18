package dev.aparikh.aipoweredsearch.limits;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Upper bounds on request input, bound from {@code search.limits.*}.
 *
 * <p>Every value here caps something that is sent to a paid model (Claude, OpenAI embeddings) or
 * fetched from Solr per request. A request over any of them gets a 400 problem-details response
 * naming the limit; see {@link WithinLimit}.
 *
 * <p>{@code max-k} cannot go below 100, the default {@code k} of hybrid search. The default is
 * applied after validation, when a request leaves {@code k} out, so a lower cap would not bound
 * those requests.
 *
 * @param maxK largest {@code k} (topK) accepted by semantic and hybrid search. Hybrid search
 *     over-fetches {@code 2k} candidates per leg.
 * @param maxQueryLength largest search query, in characters
 * @param maxQuestionLength largest {@code /ask} question, in characters
 * @param maxBatchSize most documents in one batch indexing request
 * @param maxDocumentLength largest document content to index, in characters. Every document is
 *     embedded whole; text-embedding-3-small accepts at most 8,191 tokens.
 */
@Validated
@ConfigurationProperties(prefix = "search.limits")
public record InputLimits(
    @DefaultValue("200") @Min(100) int maxK,
    @DefaultValue("1000") @Positive int maxQueryLength,
    @DefaultValue("4000") @Positive int maxQuestionLength,
    @DefaultValue("100") @Positive int maxBatchSize,
    @DefaultValue("30000") @Positive int maxDocumentLength) {}
