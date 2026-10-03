package dev.aparikh.aipoweredsearch.search;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Merges ranked result lists with Reciprocal Rank Fusion (RRF).
 *
 * <p>RRF combines rankings from different retrieval strategies (keyword and vector search, or the
 * same strategy run for several rewrites of a question) by a unified score built from document
 * <em>ranks</em> rather than raw scores. That makes it robust to scoring scales that cannot be
 * compared, such as BM25 and cosine similarity.</p>
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
 * <p>{@link #fuse(List, Function)} is the general N-way algorithm. {@link #merge(List)} and the
 * two-list {@link #merge(List, List)} used by the search API are built on it.</p>
 *
 * <h2>Deterministic order</h2>
 * <p>Fused results are ordered by:</p>
 * <ol>
 *   <li>fused score, descending;</li>
 *   <li>best single rank, ascending (a document's best position in any one list);</li>
 *   <li>the {@linkplain Ranking#tieBreakGroup() tie-break group} of the ranking holding that best
 *       rank, ascending;</li>
 *   <li>document id.</li>
 * </ol>
 * <p>The order never depends on the order in which rankings are supplied, so it is stable even
 * when they come out of a {@code HashMap}. Each document's contributions are also summed
 * largest-first, so equal rank sets give bit-identical scores. For two rankings with groups
 * {@code keyword = 0, vector = 1} this reproduces, exactly, the order this class has always
 * produced: an exact tie can only arise between documents with the same multiset of ranks, and
 * then the one whose best rank came from the keyword list was inserted, and therefore sorted,
 * first.</p>
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

    /** Name of the keyword ranking in the two-list merge; output fields are {@code keyword_*}. */
    public static final String KEYWORD = "keyword";

    /** Name of the vector ranking in the two-list merge; output fields are {@code vector_*}. */
    public static final String VECTOR = "vector";

    /**
     * One ranked list to fuse.
     *
     * @param items          the list, best first; an item's rank is its 1-based position
     * @param tieBreakGroup  orders documents whose score and best rank are equal: the one whose best
     *                       rank came from the lower group sorts first
     * @param <T>            item type
     */
    public record Ranking<T>(List<T> items, int tieBreakGroup) {
    }

    /**
     * A named map-based ranking for {@link #merge(List)}. Output fields are
     * {@code <name>_rank} and {@code <name>_score}.
     */
    public record NamedRanking(String name, List<Map<String, Object>> results) {
    }

    /**
     * One fused document.
     *
     * @param id          document id
     * @param score       fused RRF score
     * @param bestRank    best (lowest) 1-based rank in any one ranking
     * @param bestGroup   tie-break group of the ranking holding {@code bestRank}
     * @param ranks       1-based rank per input ranking (same order as the input), or 0 when absent
     * @param occurrences the item from each ranking that contains it, best rank first
     * @param <T>         item type
     */
    public record Fused<T>(String id, double score, int bestRank, int bestGroup, List<Integer> ranks, List<T> occurrences) {

        /** The item at the document's best rank. */
        public T best() {
            return occurrences.getFirst();
        }
    }

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
     * Fuses any number of rankings with one RRF pass.
     *
     * <p>A document is identified by {@code idOf}. If it appears more than once in the same
     * ranking, only its first (best) position counts. The output is de-duplicated, ordered as
     * described in the class documentation, and never thresholded.</p>
     *
     * @param rankings the rankings to fuse; may be empty
     * @param idOf     extracts a document's id
     * @param <T>      item type
     * @return fused documents, best first; never null
     */
    public <T> List<Fused<T>> fuse(List<Ranking<T>> rankings, Function<T, String> idOf) {
        Map<String, Accumulator<T>> byId = new LinkedHashMap<>();
        for (int r = 0; r < rankings.size(); r++) {
            Ranking<T> ranking = rankings.get(r);
            List<T> items = ranking.items();
            for (int i = 0; i < items.size(); i++) {
                T item = items.get(i);
                String id = idOf.apply(item);
                Accumulator<T> accumulator = byId.computeIfAbsent(id, key -> new Accumulator<>(key, rankings.size()));
                if (accumulator.ranks[r] != 0) {
                    continue; // repeated within one ranking: the first position counts
                }
                accumulator.add(r, i + 1, ranking.tieBreakGroup(), item, k);
            }
        }

        List<Fused<T>> fused = new ArrayList<>(byId.size());
        for (Accumulator<T> accumulator : byId.values()) {
            fused.add(accumulator.toFused());
        }
        fused.sort(Comparator.<Fused<T>>comparingDouble(Fused::score).reversed()
                .thenComparingInt(Fused::bestRank)
                .thenComparingInt(Fused::bestGroup)
                .thenComparing(Fused::id));
        return fused;
    }

    /**
     * Fuses any number of named, map-based rankings.
     *
     * <p>Each output map holds the document's fields, where a later ranking's value wins on
     * conflict (except {@code id} and {@code score}). It also holds {@code rrf_score},
     * {@code score} (same value), and per ranking {@code <name>_rank} and, when the input carried
     * a numeric {@code score}, {@code <name>_score}. Ties go to the ranking listed first.</p>
     *
     * @param rankings named rankings, in tie-break order
     * @return merged documents sorted by RRF score descending; never null
     * @throws IllegalArgumentException if any document is missing an {@code id} field
     */
    public List<Map<String, Object>> merge(List<NamedRanking> rankings) {
        List<Ranking<Map<String, Object>>> indexed = new ArrayList<>(rankings.size());
        for (int r = 0; r < rankings.size(); r++) {
            indexed.add(new Ranking<>(rankings.get(r).results(), r));
        }

        List<Fused<Map<String, Object>>> fused = fuse(indexed, this::extractDocId);
        List<Map<String, Object>> merged = new ArrayList<>(fused.size());
        for (Fused<Map<String, Object>> document : fused) {
            merged.add(toMergedDocument(document, rankings));
        }
        log.debug("RRF merge complete: {} unique documents after fusion of {} rankings", merged.size(), rankings.size());
        return merged;
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
    public List<Map<String, Object>> merge(@Nullable List<Map<String, Object>> keywordResults,
                                           @Nullable List<Map<String, Object>> vectorResults) {
        List<Map<String, Object>> safeKeyword = keywordResults != null ? keywordResults : List.of();
        List<Map<String, Object>> safeVector = vectorResults != null ? vectorResults : List.of();

        log.debug("Merging {} keyword results and {} vector results using RRF (k={})",
                safeKeyword.size(), safeVector.size(), k);

        return merge(List.of(new NamedRanking(KEYWORD, safeKeyword), new NamedRanking(VECTOR, safeVector)));
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
    @Nullable Double extractScore(Map<String, Object> document) {
        Object score = document.get(SCORE_FIELD);
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    private Map<String, Object> toMergedDocument(Fused<Map<String, Object>> document, List<NamedRanking> rankings) {
        // Fields from each ranking in input order, later rankings winning on conflict; this is
        // the long-standing "vector values take precedence" rule for the two-list merge.
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, Object> scores = new LinkedHashMap<>();
        Map<String, Object> ranks = new LinkedHashMap<>();
        for (int r = 0; r < rankings.size(); r++) {
            int rank = document.ranks().get(r);
            if (rank == 0) {
                continue;
            }
            Map<String, Object> source = rankings.get(r).results().get(rank - 1);
            if (fields.isEmpty()) {
                fields.putAll(source);
            } else {
                source.forEach((key, value) -> {
                    if (!ID_FIELD.equals(key) && !SCORE_FIELD.equals(key)) {
                        fields.put(key, value);
                    }
                });
            }
            String name = rankings.get(r).name();
            Double score = extractScore(source);
            if (score != null) {
                scores.put(name + "_score", score);
            }
            ranks.put(name + "_rank", rank);
        }

        Map<String, Object> merged = new LinkedHashMap<>(fields);
        merged.put(RRF_SCORE_FIELD, document.score());
        merged.put(SCORE_FIELD, document.score());
        merged.putAll(scores);
        merged.putAll(ranks);
        return merged;
    }

    /**
     * Collects one document's contributions while fusing.
     */
    private static final class Accumulator<T> {

        private final String id;
        private final int[] ranks;
        private final List<Double> contributions = new ArrayList<>();
        private final List<Occurrence<T>> occurrences = new ArrayList<>();

        Accumulator(String id, int rankingCount) {
            this.id = id;
            this.ranks = new int[rankingCount];
        }

        void add(int ranking, int rank, int group, T item, int k) {
            ranks[ranking] = rank;
            contributions.add(1.0 / (k + rank));
            occurrences.add(new Occurrence<>(rank, group, item));
        }

        Fused<T> toFused() {
            // Largest contribution first: the sum is then independent of input order.
            double score = contributions.stream()
                    .sorted(Comparator.reverseOrder())
                    .mapToDouble(Double::doubleValue)
                    .sum();
            List<Occurrence<T>> ordered = occurrences.stream()
                    .sorted(Comparator.<Occurrence<T>>comparingInt(Occurrence::rank).thenComparingInt(Occurrence::group))
                    .toList();
            Occurrence<T> best = Objects.requireNonNull(ordered.getFirst());
            return new Fused<>(id, score, best.rank(), best.group(), Arrays.stream(ranks).boxed().toList(),
                    ordered.stream().map(Occurrence::item).toList());
        }
    }

    private record Occurrence<T>(int rank, int group, T item) {
    }
}
