package dev.aparikh.aipoweredsearch.search.rag;

import dev.aparikh.aipoweredsearch.search.RrfMerger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fuses every retrieval leg of every query into one candidate list with a single N-way
 * Reciprocal Rank Fusion pass (W3, #35).
 *
 * <p>{@code RetrievalAugmentationAdvisor} retrieves each expanded query separately and hands the
 * joiner a {@code Map<Query, List<List<Document>>>}. Concatenating those lists, as the
 * pass-through joiner did, yields duplicates and an order that means nothing across queries.
 * Instead, this joiner:</p>
 * <ol>
 *   <li>splits each retrieved list into rankings by {@link RagContextKeys#LEG} (the retriever
 *       returns the BM25 and kNN hits unfused, each tagged with its leg and 1-based
 *       {@link RagContextKeys#LEG_RANK});</li>
 *   <li>runs one RRF pass over all of them ({@link RrfMerger#fuse}), so a document ranked well by
 *       several legs or several queries outranks one ranked well by a single list;</li>
 *   <li>keeps one instance per document id, sets {@link Document#getScore()} to the fused score
 *       and carries {@code rrf_score}, {@code keyword_rank}/{@code vector_rank} (best across
 *       queries) and the per-leg scores into the metadata;</li>
 *   <li>caps the result at {@code topK}. It never thresholds the fused score: RRF scores are
 *       rank-derived and carry no absolute meaning.</li>
 * </ol>
 *
 * <p>Ties are broken by best single rank, then keyword leg before vector leg, then document id, so
 * the order does not depend on the map's iteration order. With a single query this reproduces
 * {@code SearchRepository.executeHybridRerankSearch()}'s fused order exactly (see
 * {@link RrfMerger}).</p>
 *
 * <p>Lists whose documents carry no leg tag (from some other retriever) are treated as one
 * already-ranked list each, ranked after the keyword and vector legs on ties.</p>
 *
 * <p>Pattern: <a href="https://thetalkingapp.medium.com/spring-ai-recipe-better-rag-results-with-hybrid-search-6e6ab2d09003">Better
 * RAG Results with Hybrid Search</a>; RRF: Cormack, Clarke &amp; Büttcher, SIGIR 2009.</p>
 */
public final class RrfDocumentJoiner implements DocumentJoiner {

    private static final Logger log = LoggerFactory.getLogger(RrfDocumentJoiner.class);

    /** {@link RagContextKeys#LEG} value for a BM25 hit; the retriever writes it, this joiner reads it. */
    public static final String KEYWORD_LEG = "keyword";
    /** {@link RagContextKeys#LEG} value for a kNN hit; the retriever writes it, this joiner reads it. */
    public static final String VECTOR_LEG = "vector";
    static final String UNTAGGED = "untagged";

    static final String RRF_SCORE = "rrf_score";

    /** Bookkeeping keys that describe one leg's hit, not the fused document. */
    private static final Set<String> PER_HIT_KEYS = Set.of(RagContextKeys.LEG, RagContextKeys.LEG_RANK);

    private final RrfMerger merger;
    private final int topK;

    /**
     * @param k    the RRF smoothing constant; must be positive
     * @param topK how many fused candidates to keep; must be positive
     */
    public RrfDocumentJoiner(int k, int topK) {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive, got: " + topK);
        }
        this.merger = new RrfMerger(k);
        this.topK = topK;
    }

    @Override
    public List<Document> join(Map<Query, List<List<Document>>> documentsForQuery) {
        List<RrfMerger.Ranking<Document>> rankings = new ArrayList<>();
        for (List<List<Document>> lists : documentsForQuery.values()) {
            for (List<Document> list : lists) {
                rankings.addAll(rankingsByLeg(list));
            }
        }

        List<RrfMerger.Fused<Document>> fused = merger.fuse(rankings, Document::getId);
        List<Document> joined = fused.stream()
                .limit(topK)
                .map(RrfDocumentJoiner::toDocument)
                .toList();

        log.debug("RRF joined {} rankings from {} queries into {} unique documents, kept {}",
                rankings.size(), documentsForQuery.size(), fused.size(), joined.size());
        return joined;
    }

    /**
     * Splits one retrieved list into one ranking per leg, each ordered by its leg rank.
     */
    private static List<RrfMerger.Ranking<Document>> rankingsByLeg(List<Document> documents) {
        Map<String, List<Document>> byLeg = new LinkedHashMap<>();
        for (Document document : documents) {
            byLeg.computeIfAbsent(legOf(document), leg -> new ArrayList<>()).add(document);
        }

        List<RrfMerger.Ranking<Document>> rankings = new ArrayList<>(byLeg.size());
        byLeg.forEach((leg, hits) -> {
            List<Document> ordered = UNTAGGED.equals(leg)
                    ? hits
                    : hits.stream().sorted(Comparator.comparingInt(RrfDocumentJoiner::legRankOf)).toList();
            rankings.add(new RrfMerger.Ranking<>(ordered, tieBreakGroup(leg)));
        });
        return rankings;
    }

    /**
     * Builds the fused document from its best occurrence. Only text, metadata and score carry
     * over; media is dropped, which is fine for text RAG, where the retriever produces text only.
     */
    private static Document toDocument(RrfMerger.Fused<Document> fused) {
        Document best = fused.best();

        // Metadata from every occurrence, best first (Fused.occurrences() is ordered by rank, then
        // tie-break group): the best occurrence wins on conflict via putIfAbsent, and each leg's
        // own score (keyword_score / vector_score) survives.
        Map<String, Object> metadata = new LinkedHashMap<>();
        Integer bestKeywordRank = null;
        Integer bestVectorRank = null;
        for (Document occurrence : fused.occurrences()) {
            occurrence.getMetadata().forEach((key, value) -> {
                if (!PER_HIT_KEYS.contains(key)) {
                    metadata.putIfAbsent(key, value);
                }
            });
            String leg = legOf(occurrence);
            if (KEYWORD_LEG.equals(leg) && bestKeywordRank == null) {
                bestKeywordRank = legRankOf(occurrence);
            } else if (VECTOR_LEG.equals(leg) && bestVectorRank == null) {
                bestVectorRank = legRankOf(occurrence);
            }
        }
        metadata.put(RRF_SCORE, fused.score());
        if (bestKeywordRank != null) {
            metadata.put("keyword_rank", bestKeywordRank);
        }
        if (bestVectorRank != null) {
            metadata.put("vector_rank", bestVectorRank);
        }

        return Document.builder()
                .id(fused.id())
                .text(Objects.requireNonNullElse(best.getText(), ""))
                .metadata(metadata)
                .score(fused.score())
                .build();
    }

    static String legOf(Document document) {
        Object leg = document.getMetadata().get(RagContextKeys.LEG);
        return leg == null ? UNTAGGED : leg.toString();
    }

    static int legRankOf(Document document) {
        Object rank = document.getMetadata().get(RagContextKeys.LEG_RANK);
        return rank instanceof Number number ? number.intValue() : Integer.MAX_VALUE;
    }

    /** Keyword before vector before anything else, as RRF ties have always been broken. */
    static int tieBreakGroup(String leg) {
        return switch (leg) {
            case KEYWORD_LEG -> 0;
            case VECTOR_LEG -> 1;
            default -> 2;
        };
    }
}
