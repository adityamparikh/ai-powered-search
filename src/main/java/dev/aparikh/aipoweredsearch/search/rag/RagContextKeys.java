package dev.aparikh.aipoweredsearch.search.rag;

/**
 * Keys under which RAG stages exchange per-query data through {@code Query.context()}.
 *
 * <p>{@code RetrievalAugmentationAdvisor} builds the original query's context from the request
 * context, as a mutable map it owns. Query expansion runs on the caller thread before retrieval
 * is submitted, and post-processing runs on that same thread after every retrieval has joined.
 * A value an expander writes into the original query's context is therefore visible to the
 * post-processors without a {@code ThreadLocal} (W0 finding A1). Each expanded query carries its
 * own copy of the context, so retrieval threads never share a map.</p>
 *
 * <p>Every key is optional: a stage that finds its key absent falls back to today's behaviour.</p>
 */
public final class RagContextKeys {

    /**
     * The context-free rewrite of the user's question ({@code String}). Post-processors judge
     * relevance against it instead of the raw follow-up. Written by the query planner (W1).
     */
    public static final String STANDALONE = "rag.standalone";

    /**
     * Text for the BM25 leg ({@code String}): distinctive terms rather than a chatty question.
     * Falls back to {@code Query.text()}. Written by the query planner (W1).
     */
    public static final String KEYWORD_QUERY = "rag.keywordQuery";

    /**
     * Text to embed for the kNN leg ({@code String}), e.g. a HyDE passage. Falls back to
     * {@code Query.text()}. Written by the query planner when HyDE is on (W2).
     */
    public static final String VECTOR_TEXT = "rag.vectorText";

    /**
     * A precomputed query embedding ({@code float[]}). When present, the kNN leg uses it and makes
     * no embedding call. Written by the batched embedder (W2), read by the retriever (W5).
     */
    public static final String VECTOR = "rag.vector";

    /**
     * Validated Solr filter queries ({@code List<String>}) applied as {@code fq} on both legs.
     * Written by the query planner when filters are on (W1).
     */
    public static final String FILTERS = "rag.filters";

    /**
     * Document metadata: which retrieval leg produced an unfused hit ({@code "keyword"} or
     * {@code "vector"}). Written by the retriever, read by the RRF joiner (W3).
     */
    public static final String LEG = "rag.leg";

    /**
     * Document metadata: 1-based rank of an unfused hit within its leg ({@code Integer}).
     * Written by the retriever, read by the RRF joiner (W3).
     */
    public static final String LEG_RANK = "rag.legRank";

    private RagContextKeys() {
    }
}
