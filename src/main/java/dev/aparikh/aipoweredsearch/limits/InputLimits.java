package dev.aparikh.aipoweredsearch.limits;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Upper bounds on request input, bound from {@code search.limits.*}.
 *
 * <p>Every value here caps something that is sent to a paid model (Claude, OpenAI embeddings),
 * fetched from Solr, or stored in Solr per request. A request over any of them gets a 400
 * problem-details response naming the limit; see {@link WithinLimit}. Lengths are counted in
 * Unicode code points.
 *
 * <p>{@code max-k} cannot go below the largest default {@code k} ({@link #DEFAULT_HYBRID_K}). The
 * default is applied after validation, when a request leaves {@code k} out, so a lower cap would
 * not bound those requests.
 *
 * @param maxK largest {@code k} (topK) accepted by semantic and hybrid search. Hybrid search
 *     over-fetches {@code 2k} candidates per leg.
 * @param maxQueryLength longest search query
 * @param maxQuestionLength longest {@code /ask} question
 * @param maxBatchSize most documents in one batch indexing request
 * @param maxBatchContentLength most content, summed over the documents of one batch. A batch is
 *     embedded in a single OpenAI request, which takes at most 300,000 tokens across all inputs.
 *     The default of 150,000 code points leaves room for up to two tokens per code point (code,
 *     non-Latin scripts); English runs at about four code points per token.
 * @param maxDocumentLength longest content of one document. Each document is one embedding input,
 *     and text-embedding-3-small takes at most 8,191 tokens per input; 30,000 code points is about
 *     that for English, but denser text can exceed it. Splitting documents is issue #96.
 * @param maxIdLength longest document id
 * @param maxMetadataLength largest document metadata, measured as the code points of every key and
 *     value, nested ones included, with each entry, element and container counting at least one.
 *     Metadata is not embedded, but it is stored in Solr.
 */
@Validated
@ConfigurationProperties(prefix = "search.limits")
public record InputLimits(
    @DefaultValue("200") @Min(LARGEST_DEFAULT_K) int maxK,
    @DefaultValue("1000") @Positive int maxQueryLength,
    @DefaultValue("4000") @Positive int maxQuestionLength,
    @DefaultValue("100") @Positive int maxBatchSize,
    @DefaultValue("150000") @Positive int maxBatchContentLength,
    @DefaultValue("30000") @Positive int maxDocumentLength,
    @DefaultValue("512") @Positive int maxIdLength,
    @DefaultValue("10000") @Positive int maxMetadataLength) {

  /** The {@code k} semantic search uses when a request leaves it out. */
  public static final int DEFAULT_SEMANTIC_K = 50;

  /** The {@code k} hybrid search uses when a request leaves it out. */
  public static final int DEFAULT_HYBRID_K = 100;

  /** The floor for {@code max-k}: no default {@code k} may exceed the cap. */
  static final int LARGEST_DEFAULT_K =
      DEFAULT_HYBRID_K > DEFAULT_SEMANTIC_K ? DEFAULT_HYBRID_K : DEFAULT_SEMANTIC_K;
}
