package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import dev.aparikh.aipoweredsearch.solr.vectorstore.SolrVectorStoreOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Spring AI {@link DocumentRetriever} that retrieves RAG context using hybrid search.
 *
 * <p>Spring AI's own {@link VectorStoreDocumentRetriever} is
 * purely dense: it embeds the question and returns the nearest vectors. That misses documents whose
 * relevance is lexical — exact product codes, error strings, API names, rare proper nouns — because
 * an embedding of a short question rarely lands near them. This retriever instead delegates to
 * {@link SearchRepository#executeHybridRerankSearch}, which runs a BM25 keyword search and a KNN
 * vector search over the same Solr collection and fuses them with Reciprocal Rank Fusion, so both
 * kinds of relevance reach the model.</p>
 *
 * <p>Plug it into a {@code RetrievalAugmentationAdvisor} in place of the vector-only retriever:</p>
 * <pre>{@code
 * RetrievalAugmentationAdvisor.builder()
 *         .documentRetriever(HybridDocumentRetriever.builder(searchRepository)
 *                 .collection("books")
 *                 .topK(5)
 *                 .similarityThreshold(0.3)
 *                 .build())
 *         .build();
 * }</pre>
 *
 * <p>Instances are immutable and safe for concurrent use.</p>
 *
 * @author Aditya Parikh
 * @since 1.0.0
 */
public class HybridDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridDocumentRetriever.class);

    /** Default number of documents handed to the model as context. */
    static final int DEFAULT_TOP_K = 5;

    /** Solr's internal document version field, never useful as RAG metadata. */
    private static final String VERSION_FIELD = "_version_";

    /**
     * Fields requested from Solr. Deliberately excludes the raw {@code vector} field: at 1536
     * dimensions per document it would dominate the response payload while contributing nothing to
     * the generated answer. Mirrors the projection {@code SolrVectorStore} uses.
     */
    private static final String DEFAULT_FIELDS_CSV =
            SolrVectorStoreOptions.DEFAULT_ID_FIELD
                    + "," + SolrVectorStoreOptions.DEFAULT_CONTENT_FIELD
                    + "," + SolrVectorStoreOptions.DEFAULT_METADATA_PREFIX + "*";

    private final SearchRepository searchRepository;
    private final String collection;
    private final String contentFieldName;
    private final String idFieldName;
    private final int topK;
    private final Double similarityThreshold;
    private final String filterExpression;
    private final String fieldsCsv;

    private HybridDocumentRetriever(Builder builder) {
        Assert.notNull(builder.searchRepository, "searchRepository must not be null");
        Assert.hasText(builder.collection, "collection must not be null or empty");
        Assert.isTrue(builder.topK > 0, "topK must be positive, got: " + builder.topK);
        if (builder.similarityThreshold != null) {
            Assert.isTrue(builder.similarityThreshold >= 0.0 && builder.similarityThreshold <= 1.0,
                    "similarityThreshold must be in [0..1], got: " + builder.similarityThreshold);
        }

        this.searchRepository = builder.searchRepository;
        this.collection = builder.collection;
        this.contentFieldName = builder.contentFieldName;
        this.idFieldName = builder.idFieldName;
        this.topK = builder.topK;
        this.similarityThreshold = builder.similarityThreshold;
        this.filterExpression = builder.filterExpression;
        this.fieldsCsv = builder.fieldsCsv;
    }

    /**
     * Retrieves context documents for a RAG query using hybrid keyword + vector search.
     *
     * <p>Retrieval failures are not propagated. A RAG pipeline that cannot reach Solr should still
     * be able to answer from conversation history rather than failing the whole request, so an
     * error is logged and an empty list returned.</p>
     *
     * @param query the RAG query; must not be null and must carry non-blank text
     * @return the fused top-K documents, or an empty list if retrieval found nothing or failed
     */
    @Override
    public List<Document> retrieve(Query query) {
        // No blank-text guard is needed: Query's constructor asserts non-blank text, so a Query
        // instance carrying blank text cannot exist. Blank input is rejected at the API boundary
        // (see AskRequest) before the advisor chain builds a Query at all.
        Assert.notNull(query, "query must not be null");

        log.debug("Hybrid RAG retrieval from collection '{}' for query: {}", collection, query.text());

        SearchResponse response;
        try {
            response = searchRepository.executeHybridRerankSearch(
                    collection, query.text(), topK, filterExpression, fieldsCsv, similarityThreshold);
        } catch (Exception e) {
            log.error("Hybrid retrieval failed for collection '{}'; continuing without RAG context", collection, e);
            return List.of();
        }

        List<Map<String, Object>> results = response != null ? response.documents() : null;
        if (results == null || results.isEmpty()) {
            log.debug("Hybrid retrieval returned no documents for query: {}", query.text());
            return List.of();
        }

        List<Document> documents = new ArrayList<>(results.size());
        for (Map<String, Object> result : results) {
            Document document = toDocument(result);
            if (document != null) {
                documents.add(document);
            }
        }

        log.debug("Hybrid retrieval produced {} context documents", documents.size());
        return documents;
    }

    /**
     * Converts one Solr result map into a Spring AI {@link Document}.
     *
     * @return the document, or null if it carries no usable text
     */
    private Document toDocument(Map<String, Object> result) {
        Object textValue = result.get(contentFieldName);
        String text = textValue != null ? textValue.toString() : null;
        if (text == null || text.isBlank()) {
            // A document with no text contributes nothing to the prompt and would fail Document's
            // own content assertion, so drop it rather than passing an empty context chunk along.
            log.debug("Skipping retrieved document with no '{}' value: {}", contentFieldName, result.get(idFieldName));
            return null;
        }

        Document.Builder builder = Document.builder().text(text);

        Object id = result.get(idFieldName);
        if (id != null) {
            builder.id(id.toString());
        }

        // Prefer the fused RRF score, falling back to the plain score: when hybrid search degrades
        // to its keyword-only or vector-only cascade the results carry no rrf_score, and the
        // document would otherwise arrive with no score at all.
        Object score = result.get(RrfMerger.RRF_SCORE_FIELD);
        if (!(score instanceof Number)) {
            score = result.get(RrfMerger.SCORE_FIELD);
        }
        if (score instanceof Number number) {
            builder.score(number.doubleValue());
        }

        builder.metadata(extractMetadata(result));
        return builder.build();
    }

    /**
     * Copies everything except the text, the id and Solr bookkeeping into document metadata.
     *
     * <p>The RRF provenance fields ({@code rrf_score}, {@code keyword_rank}, {@code vector_rank}
     * and the per-leg scores) are kept: they are small, and they are what lets a caller see whether
     * a given piece of context arrived through the keyword leg, the vector leg or both. The generic
     * {@code score} field is dropped — {@link RrfMerger} sets it as a duplicate of {@code rrf_score},
     * and it has already been promoted to the document's own score.</p>
     */
    private Map<String, Object> extractMetadata(Map<String, Object> result) {
        Set<String> excluded = Set.of(
                contentFieldName,
                idFieldName,
                RrfMerger.SCORE_FIELD,
                SolrVectorStoreOptions.DEFAULT_VECTOR_FIELD,
                VERSION_FIELD);

        Map<String, Object> metadata = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : result.entrySet()) {
            if (excluded.contains(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            metadata.put(entry.getKey(), entry.getValue());
        }
        return metadata;
    }

    /**
     * Creates a builder for the given repository.
     *
     * @param searchRepository the repository used to execute hybrid searches
     * @return a new builder
     */
    public static Builder builder(SearchRepository searchRepository) {
        return new Builder(searchRepository);
    }

    /**
     * Builder for {@link HybridDocumentRetriever}.
     */
    public static class Builder {

        private final SearchRepository searchRepository;
        private String collection;
        private String contentFieldName = SolrVectorStoreOptions.DEFAULT_CONTENT_FIELD;
        private String idFieldName = SolrVectorStoreOptions.DEFAULT_ID_FIELD;
        private int topK = DEFAULT_TOP_K;
        private Double similarityThreshold;
        private String filterExpression;
        private String fieldsCsv = DEFAULT_FIELDS_CSV;

        private Builder(SearchRepository searchRepository) {
            this.searchRepository = searchRepository;
        }

        /** Sets the Solr collection to retrieve context from. Required. */
        public Builder collection(String collection) {
            this.collection = collection;
            return this;
        }

        /** Sets the Solr field holding document text. Defaults to {@code content}. */
        public Builder contentFieldName(String contentFieldName) {
            if (contentFieldName != null && !contentFieldName.isBlank()) {
                this.contentFieldName = contentFieldName;
            }
            return this;
        }

        /** Sets the Solr field holding the document id. Defaults to {@code id}. */
        public Builder idFieldName(String idFieldName) {
            if (idFieldName != null && !idFieldName.isBlank()) {
                this.idFieldName = idFieldName;
            }
            return this;
        }

        /** Sets how many fused documents to hand to the model. Defaults to {@value #DEFAULT_TOP_K}. */
        public Builder topK(int topK) {
            this.topK = topK;
            return this;
        }

        /**
         * Sets the minimum cosine similarity for the vector leg, in {@code [0..1]}.
         *
         * <p>As documented on {@link SearchRepository#executeHybridRerankSearch}, this filters the
         * vector candidates before fusion; it is not a threshold on the fused RRF score.</p>
         */
        public Builder similarityThreshold(Double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
            return this;
        }

        /** Sets an optional Solr filter query applied to both legs of the search. */
        public Builder filterExpression(String filterExpression) {
            this.filterExpression = filterExpression;
            return this;
        }

        /** Overrides the Solr field projection. Defaults to id, content and metadata fields. */
        public Builder fieldsCsv(String fieldsCsv) {
            this.fieldsCsv = fieldsCsv;
            return this;
        }

        public HybridDocumentRetriever build() {
            return new HybridDocumentRetriever(this);
        }
    }
}
