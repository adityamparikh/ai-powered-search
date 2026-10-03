package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.search.rag.RagContextKeys;
import dev.aparikh.aipoweredsearch.search.rag.RrfDocumentJoiner;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3 (#35) invariant: with a single query, the N-way fusion reproduces the pre-W3 RRF order exactly.
 *
 * <p>For 100 seeded random keyword/vector result pairs, both the generalised
 * {@link RrfMerger#merge(List, List)} and {@link RrfDocumentJoiner} are compared against
 * {@link LegacyRrfMerger}, a frozen copy of the merger as it was before W3. The generator draws
 * ids from a small pool so overlaps, and with them exact score ties, are common.</p>
 */
class RrfEquivalenceTest {

    private static final int CASES = 100;
    private static final int TOP_K = 20;

    record Case(int seed, List<Map<String, Object>> keyword, List<Map<String, Object>> vector) {
        @Override
        public String toString() {
            return "seed " + seed + " (" + keyword.size() + " keyword, " + vector.size() + " vector)";
        }
    }

    static Stream<Case> randomCases() {
        return IntStream.range(0, CASES).mapToObj(seed -> {
            Random random = new Random(seed);
            return new Case(seed, randomRanking(random, "keyword"), randomRanking(random, "vector"));
        });
    }

    private static List<Map<String, Object>> randomRanking(Random random, String leg) {
        List<String> pool = new ArrayList<>(IntStream.range(0, 50).mapToObj(i -> "doc-" + i).toList());
        Collections.shuffle(pool, random);
        int size = random.nextInt(41); // 0..40, like a 2 * topK over-fetch
        List<Map<String, Object>> ranking = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", pool.get(i));
            row.put("content", "text of " + pool.get(i));
            row.put("metadata_source", leg);
            row.put("score", 100.0 - i);
            ranking.add(row);
        }
        return ranking;
    }

    @ParameterizedTest
    @MethodSource("randomCases")
    void twoListMergeIsIdenticalToTheLegacyMerger(Case c) {
        List<Map<String, Object>> legacy = new LegacyRrfMerger().merge(c.keyword(), c.vector());
        List<Map<String, Object>> current = new RrfMerger().merge(c.keyword(), c.vector());

        // Same documents, same order, same fields and values (scores, ranks, merged fields).
        assertThat(current).isEqualTo(legacy);
    }

    @ParameterizedTest
    @MethodSource("randomCases")
    void joinerOverOneQueryIsIdenticalToTheLegacyMergerCappedAtTopK(Case c) {
        List<String> legacy = new LegacyRrfMerger().merge(c.keyword(), c.vector()).stream()
                .limit(TOP_K)
                .map(row -> String.valueOf(row.get("id")))
                .toList();

        List<Document> hits = new ArrayList<>(tagged(c.keyword(), "keyword"));
        hits.addAll(tagged(c.vector(), "vector"));
        List<String> joined = new RrfDocumentJoiner(60, TOP_K)
                .join(Map.of(new Query("q"), List.of(hits))).stream()
                .map(Document::getId)
                .toList();

        assertThat(joined).containsExactlyElementsOf(legacy);
    }

    private static List<Document> tagged(List<Map<String, Object>> rows, String leg) {
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            documents.add(new Document(String.valueOf(row.get("id")), String.valueOf(row.get("content")),
                    Map.of(RagContextKeys.LEG, leg, RagContextKeys.LEG_RANK, i + 1)));
        }
        return documents;
    }
}
