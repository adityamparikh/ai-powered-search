package dev.aparikh.aipoweredsearch.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges search results from multiple retrieval strategies using Reciprocal Rank Fusion (RRF).
 *
 * <p>RRF combines results from different search methods (e.g., keyword and vector search)
 * by computing a unified score based on document ranks rather than raw scores. This is more
 * robust than score averaging because it normalizes different scoring scales.</p>
 *
 * <p>The RRF formula is: {@code score = sum(1 / (k + rank))} where:
 * <ul>
 *   <li>{@code k} is a smoothing constant (default 60) that prevents top ranks from dominating</li>
 *   <li>{@code rank} is the 1-indexed position of the document in each result list</li>
 * </ul>
 *
 * <p>Example: A document at rank 3 in keyword results and rank 5 in vector results:
 * {@code rrf_score = 1/(60+3) + 1/(60+5) = 0.0159 + 0.0154 = 0.0313}</p>
 *
 * <p>This class is thread-safe and immutable after construction.</p>
 *
 * @author Aditya Parikh
 * @since 1.0.0
 */
public class RrfMerger {

    private static final Logger log = LoggerFactory.getLogger(RrfMerger.class);

    static final int DEFAULT_K = 60;
    static final String ID_FIELD = "id";
    static final String RRF_SCORE_FIELD = "rrf_score";
    static final String KEYWORD_SCORE_FIELD = "keyword_score";
    static final String VECTOR_SCORE_FIELD = "vector_score";
    static final String KEYWORD_RANK_FIELD = "keyword_rank";
    static final String VECTOR_RANK_FIELD = "vector_rank";
    static final String SCORE_FIELD = "score";

    private final int k;

    /**
     * Creates an RrfMerger with the default k parameter (60).
     */
    public RrfMerger() {
        this(DEFAULT_K);
    }

    /**
     * Creates an RrfMerger with a custom k parameter.
     *
     * @param k the smoothing constant for the RRF formula; must be positive.
     *          Higher values give more uniform weighting across ranks;
     *          lower values give more weight to top-ranked documents.
     * @throws IllegalArgumentException if k is not positive
     */
    public RrfMerger(int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("k parameter must be positive, got: " + k);
        }
        this.k = k;
        log.debug("RrfMerger initialized with k={}", k);
    }

    /**
     * Returns the k parameter used by this merger.
     */
    public int getK() {
        return k;
    }

    /**
     * Merges keyword and vector search results using the RRF algorithm.
     *
     * <p>Documents are identified by their {@code id} field. If a document appears in both
     * result sets, it receives RRF score contributions from both. Documents appearing in
     * only one set receive a score contribution from that set only.</p>
     *
     * <p>When a document appears in both lists, fields are merged with vector result values
     * taking precedence on conflict (except for the {@code id} field which is always preserved).</p>
     *
     * <p>The output documents contain the following additional fields:
     * <ul>
     *   <li>{@code rrf_score}: the combined RRF score</li>
     *   <li>{@code score}: same as rrf_score (for compatibility)</li>
     *   <li>{@code keyword_score}: original score from keyword search (if present)</li>
     *   <li>{@code vector_score}: original score from vector search (if present)</li>
     *   <li>{@code keyword_rank}: rank in keyword results (if present)</li>
     *   <li>{@code vector_rank}: rank in vector results (if present)</li>
     * </ul>
     *
     * @param keywordResults results from keyword/lexical search, ordered by relevance; may be null
     * @param vectorResults  results from vector/semantic search, ordered by similarity; may be null
     * @return merged results sorted by RRF score descending; never null
     * @throws IllegalArgumentException if any document is missing an {@code id} field
     */
    public List<Map<String, Object>> merge(List<Map<String, Object>> keywordResults,
                                           List<Map<String, Object>> vectorResults) {
        List<Map<String, Object>> safeKeyword = keywordResults != null ? keywordResults : Collections.emptyList();
        List<Map<String, Object>> safeVector = vectorResults != null ? vectorResults : Collections.emptyList();

        log.debug("Merging {} keyword results and {} vector results using RRF (k={})",
                safeKeyword.size(), safeVector.size(), k);

        if (safeKeyword.isEmpty() && safeVector.isEmpty()) {
            return Collections.emptyList();
        }

        // LinkedHashMap preserves insertion order for deterministic iteration
        Map<String, MergedDocument> documentMap = new LinkedHashMap<>();

        // Process keyword results (1-indexed ranks)
        for (int i = 0; i < safeKeyword.size(); i++) {
            Map<String, Object> doc = safeKeyword.get(i);
            String docId = extractDocId(doc);
            int rank = i + 1;
            double rrfContribution = 1.0 / (k + rank);

            MergedDocument merged = documentMap.computeIfAbsent(docId,
                    id -> new MergedDocument(id, doc));
            merged.addKeywordScore(rrfContribution, rank, extractScore(doc));

            log.trace("Keyword doc {}: rank={}, rrf={}", docId, rank, rrfContribution);
        }

        // Process vector results (1-indexed ranks)
        for (int i = 0; i < safeVector.size(); i++) {
            Map<String, Object> doc = safeVector.get(i);
            String docId = extractDocId(doc);
            int rank = i + 1;
            double rrfContribution = 1.0 / (k + rank);

            MergedDocument merged = documentMap.get(docId);
            if (merged == null) {
                // Document only in vector results
                merged = new MergedDocument(docId, doc);
                documentMap.put(docId, merged);
            } else {
                // Document in both — merge fields, preferring vector values
                merged.mergeVectorFields(doc);
            }
            merged.addVectorScore(rrfContribution, rank, extractScore(doc));

            log.trace("Vector doc {}: rank={}, rrf={}", docId, rank, rrfContribution);
        }

        // Build final result list with all scoring metadata
        List<Map<String, Object>> mergedResults = new ArrayList<>(documentMap.size());
        for (MergedDocument merged : documentMap.values()) {
            mergedResults.add(merged.toDocument());
        }

        // Sort by RRF score descending
        mergedResults.sort(Comparator.comparingDouble(
                (Map<String, Object> d) -> ((Number) d.get(RRF_SCORE_FIELD)).doubleValue()).reversed());

        log.debug("RRF merge complete: {} unique documents after fusion", mergedResults.size());
        return mergedResults;
    }

    /**
     * Extracts the document ID from a result map.
     *
     * @throws IllegalArgumentException if the document has no {@code id} field
     */
    String extractDocId(Map<String, Object> document) {
        Object id = document.get(ID_FIELD);
        if (id == null) {
            throw new IllegalArgumentException("Document missing required 'id' field: " + document.keySet());
        }
        return id.toString();
    }

    /**
     * Extracts the score from a document as a Double, returning null if absent or non-numeric.
     */
    Double extractScore(Map<String, Object> document) {
        Object score = document.get(SCORE_FIELD);
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    /**
     * Tracks a single document's data during RRF merging.
     */
    static class MergedDocument {
        final String id;
        final Map<String, Object> fields;
        double rrfScore;
        Double keywordOriginalScore;
        Double vectorOriginalScore;
        Integer keywordRank;
        Integer vectorRank;

        MergedDocument(String id, Map<String, Object> sourceDocument) {
            this.id = id;
            // Copy so we don't mutate the caller's map
            this.fields = new LinkedHashMap<>(sourceDocument);
            this.rrfScore = 0.0;
        }

        void addKeywordScore(double rrfContribution, int rank, Double originalScore) {
            this.rrfScore += rrfContribution;
            this.keywordRank = rank;
            this.keywordOriginalScore = originalScore;
        }

        void addVectorScore(double rrfContribution, int rank, Double originalScore) {
            this.rrfScore += rrfContribution;
            this.vectorRank = rank;
            this.vectorOriginalScore = originalScore;
        }

        /**
         * Merges fields from a vector search result into this document.
         * Vector values take precedence on conflict, except for the {@code id} field.
         */
        void mergeVectorFields(Map<String, Object> vectorDoc) {
            for (Map.Entry<String, Object> entry : vectorDoc.entrySet()) {
                String key = entry.getKey();
                if (ID_FIELD.equals(key) || SCORE_FIELD.equals(key)) {
                    continue; // Never overwrite id; score will be replaced with RRF score
                }
                fields.put(key, entry.getValue());
            }
        }

        /**
         * Produces the final document map with all RRF scoring metadata.
         */
        Map<String, Object> toDocument() {
            Map<String, Object> doc = new LinkedHashMap<>(fields);
            doc.put(RRF_SCORE_FIELD, rrfScore);
            doc.put(SCORE_FIELD, rrfScore);

            if (keywordOriginalScore != null) {
                doc.put(KEYWORD_SCORE_FIELD, keywordOriginalScore);
            }
            if (vectorOriginalScore != null) {
                doc.put(VECTOR_SCORE_FIELD, vectorOriginalScore);
            }
            if (keywordRank != null) {
                doc.put(KEYWORD_RANK_FIELD, keywordRank);
            }
            if (vectorRank != null) {
                doc.put(VECTOR_RANK_FIELD, vectorRank);
            }
            return doc;
        }
    }
}
