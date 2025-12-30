package dev.aparikh.aipoweredsearch.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for merging search results using Reciprocal Rank Fusion (RRF).
 *
 * <p>RRF is a method for combining results from multiple search strategies (e.g., keyword and vector search)
 * by computing a unified score based on document ranks rather than raw scores. This approach is more robust
 * than simple score averaging because it normalizes different scoring scales.</p>
 *
 * <p>The RRF formula is: <code>score = sum(1 / (k + rank))</code> where:
 * <ul>
 *   <li>k is a constant (default 60) that prevents early ranks from dominating</li>
 *   <li>rank is the position of the document in each result list (1-indexed)</li>
 * </ul>
 * </p>
 *
 * <p>Example: If a document appears at rank 3 in keyword results and rank 5 in vector results:
 * <code>rrf_score = 1/(60+3) + 1/(60+5) = 0.0159 + 0.0154 = 0.0313</code>
 * </p>
 *
 * @author Aditya Parikh
 * @since 1.0.0
 */
public class RrfMerger {

    private static final Logger log = LoggerFactory.getLogger(RrfMerger.class);
    private static final int DEFAULT_K = 60;
    private static final String ID_FIELD = "id";
    private static final String RRF_SCORE_FIELD = "rrf_score";
    private static final String KEYWORD_SCORE_FIELD = "keyword_score";
    private static final String VECTOR_SCORE_FIELD = "vector_score";

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
     * @param k the constant used in RRF formula (higher k gives more weight to lower ranks)
     */
    public RrfMerger(int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("k parameter must be positive, got: " + k);
        }
        this.k = k;
        log.debug("RrfMerger initialized with k={}", k);
    }

    /**
     * Merges keyword and vector search results using RRF algorithm.
     *
     * <p>Documents are identified by their "id" field. If a document appears in both result sets,
     * it receives RRF score contributions from both. Documents appearing in only one set
     * receive RRF score from that set only.</p>
     *
     * @param keywordResults results from keyword/lexical search (ranked by relevance)
     * @param vectorResults  results from vector/semantic search (ranked by similarity)
     * @return merged and re-ranked results sorted by RRF score (descending)
     */
    public List<Map<String, Object>> merge(List<Map<String, Object>> keywordResults,
                                           List<Map<String, Object>> vectorResults) {
        log.debug("Merging {} keyword results and {} vector results using RRF (k={})",
                keywordResults.size(), vectorResults.size(), k);

        // Map to store RRF scores and merged documents
        Map<String, MergedDocument> documentMap = new LinkedHashMap<>();

        // Process keyword results (rank 1-indexed)
        for (int i = 0; i < keywordResults.size(); i++) {
            Map<String, Object> doc = keywordResults.get(i);
            String docId = extractDocId(doc);
            int rank = i + 1; // 1-indexed rank
            double rrfContribution = 1.0 / (k + rank);

            MergedDocument mergedDoc = documentMap.computeIfAbsent(docId,
                    id -> new MergedDocument(docId, new HashMap<>(doc)));
            mergedDoc.addKeywordScore(rrfContribution, rank, extractScore(doc));

            log.trace("Document {} from keyword search: rank={}, rrfContribution={}", docId, rank, rrfContribution);
        }

        // Process vector results (rank 1-indexed)
        for (int i = 0; i < vectorResults.size(); i++) {
            Map<String, Object> doc = vectorResults.get(i);
            String docId = extractDocId(doc);
            int rank = i + 1; // 1-indexed rank
            double rrfContribution = 1.0 / (k + rank);

            MergedDocument mergedDoc = documentMap.get(docId);
            if (mergedDoc == null) {
                // Document only in vector results
                mergedDoc = new MergedDocument(docId, new HashMap<>(doc));
                documentMap.put(docId, mergedDoc);
            } else {
                // Document in both: merge fields (prefer vector fields if different)
                mergedDoc.mergeFields(doc);
            }
            mergedDoc.addVectorScore(rrfContribution, rank, extractScore(doc));

            log.trace("Document {} from vector search: rank={}, rrfContribution={}", docId, rank, rrfContribution);
        }

        // Convert to list and add RRF scores to documents
        List<Map<String, Object>> mergedResults = new ArrayList<>();
        for (MergedDocument mergedDoc : documentMap.values()) {
            Map<String, Object> doc = mergedDoc.document;
            doc.put(RRF_SCORE_FIELD, mergedDoc.rrfScore);

            // Include original scores for debugging/transparency
            if (mergedDoc.keywordOriginalScore != null) {
                doc.put(KEYWORD_SCORE_FIELD, mergedDoc.keywordOriginalScore);
            }
            if (mergedDoc.vectorOriginalScore != null) {
                doc.put(VECTOR_SCORE_FIELD, mergedDoc.vectorOriginalScore);
            }

            // Set main score to RRF score
            doc.put("score", mergedDoc.rrfScore);

            mergedResults.add(doc);
        }

        // Sort by RRF score descending
        mergedResults.sort((d1, d2) -> {
            Double score1 = (Double) d1.get(RRF_SCORE_FIELD);
            Double score2 = (Double) d2.get(RRF_SCORE_FIELD);
            return Double.compare(score2, score1); // Descending order
        });

        log.debug("RRF merge complete: {} unique documents after fusion", mergedResults.size());
        return mergedResults;
    }

    /**
     * Extracts document ID from a search result.
     *
     * @param document the document map
     * @return document ID as string
     * @throws IllegalArgumentException if document has no ID field
     */
    private String extractDocId(Map<String, Object> document) {
        Object id = document.get(ID_FIELD);
        if (id == null) {
            throw new IllegalArgumentException("Document missing required 'id' field: " + document);
        }
        return id.toString();
    }

    /**
     * Extracts the score from a document, returning null if not present.
     *
     * @param document the document map
     * @return score as Double or null
     */
    private Double extractScore(Map<String, Object> document) {
        Object score = document.get("score");
        if (score instanceof Number) {
            return ((Number) score).doubleValue();
        }
        return null;
    }

    /**
     * Internal class to track merged document data during RRF computation.
     */
    private static class MergedDocument {
        final String id;
        final Map<String, Object> document;
        double rrfScore;
        Double keywordOriginalScore;
        Double vectorOriginalScore;
        Integer keywordRank;
        Integer vectorRank;

        MergedDocument(String id, Map<String, Object> document) {
            this.id = id;
            this.document = document;
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

        void mergeFields(Map<String, Object> otherDoc) {
            // Merge fields from vector results, preferring vector values for conflicts
            for (Map.Entry<String, Object> entry : otherDoc.entrySet()) {
                String key = entry.getKey();
                // Don't overwrite existing fields except score (we'll replace with RRF score later)
                if (!document.containsKey(key) || "score".equals(key)) {
                    document.put(key, entry.getValue());
                }
            }
        }
    }
}
