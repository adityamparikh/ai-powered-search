package dev.aparikh.aipoweredsearch.search.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link RrfDocumentJoiner}: N-way RRF across queries and legs (W3, #35).
 */
class RrfDocumentJoinerTest {

    private static final double EPS = 1e-12;

    private final RrfDocumentJoiner joiner = new RrfDocumentJoiner(60, 20);

    private static Document hit(String id, String leg, int rank) {
        return new Document(id, "text " + id, Map.of(RagContextKeys.LEG, leg, RagContextKeys.LEG_RANK, rank));
    }

    private static List<Document> leg(String leg, String... ids) {
        List<Document> hits = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            hits.add(hit(ids[i], leg, i + 1));
        }
        return hits;
    }

    private static List<Document> legs(List<Document> keyword, List<Document> vector) {
        List<Document> all = new ArrayList<>(keyword);
        all.addAll(vector);
        return all;
    }

    private static List<String> ids(List<Document> documents) {
        return documents.stream().map(Document::getId).toList();
    }

    @Test
    void fusedScoresMatchHandComputedRrf() {
        // keyword [a, b, c], vector [b, d], k = 60:
        //   a = 1/61, b = 1/62 + 1/61, c = 1/63, d = 1/62
        List<Document> joined = joiner.join(Map.of(new Query("q"),
                List.of(legs(leg("keyword", "a", "b", "c"), leg("vector", "b", "d")))));

        assertThat(ids(joined)).containsExactly("b", "a", "d", "c");
        assertThat(joined.get(0).getScore()).isCloseTo(1.0 / 62 + 1.0 / 61, within(EPS));
        assertThat(joined.get(1).getScore()).isCloseTo(1.0 / 61, within(EPS));
        assertThat(joined.get(2).getScore()).isCloseTo(1.0 / 62, within(EPS));
        assertThat(joined.get(3).getScore()).isCloseTo(1.0 / 63, within(EPS));
    }

    @Test
    void oneRrfPassAcrossQueriesAndLegs() {
        // Two queries. 'shared' is mid-ranked in all four lists; 'solo' tops a single list.
        Map<Query, List<List<Document>>> input = new LinkedHashMap<>();
        input.put(new Query("standalone"), List.of(legs(
                leg("keyword", "solo", "x1", "shared"), leg("vector", "x2", "x3", "shared"))));
        input.put(new Query("variant"), List.of(legs(
                leg("keyword", "y1", "y2", "shared"), leg("vector", "y3", "y4", "shared"))));

        List<Document> joined = joiner.join(input);

        assertThat(ids(joined).getFirst()).isEqualTo("shared");
        assertThat(joined.getFirst().getScore()).isCloseTo(4.0 / 63, within(EPS));
        assertThat(ids(joined)).contains("solo");
    }

    @Test
    void eachDocumentIdAppearsOnce() {
        Map<Query, List<List<Document>>> input = new LinkedHashMap<>();
        input.put(new Query("q1"), List.of(legs(leg("keyword", "a", "b"), leg("vector", "a", "b"))));
        input.put(new Query("q2"), List.of(legs(leg("keyword", "b", "a"), leg("vector", "c"))));

        List<String> joined = ids(joiner.join(input));

        assertThat(joined).doesNotHaveDuplicates().containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void outputIsCappedAtTopK() {
        String[] keyword = IntStream.range(0, 40).mapToObj(i -> "k" + i).toArray(String[]::new);
        String[] vector = IntStream.range(0, 40).mapToObj(i -> "v" + i).toArray(String[]::new);

        List<Document> joined = new RrfDocumentJoiner(60, 20)
                .join(Map.of(new Query("q"), List.of(legs(leg("keyword", keyword), leg("vector", vector)))));

        assertThat(joined).hasSize(20);
    }

    @Test
    void neverThresholdsTheFusedScore() {
        // Rank-40 hits score ~0.01; nothing is dropped below the cap however low it scores.
        String[] keyword = IntStream.range(0, 5).mapToObj(i -> "k" + i).toArray(String[]::new);

        List<Document> joined = new RrfDocumentJoiner(1000, 20)
                .join(Map.of(new Query("q"), List.of(leg("keyword", keyword))));

        assertThat(joined).hasSize(5);
    }

    @Test
    void tiesBreakByBestRankThenKeywordLegThenId() {
        // 'zeta' is keyword #1 only and 'alpha' is vector #1 only: equal scores, equal best rank.
        // Keyword wins the tie despite the id order, as RRF ties always have.
        List<Document> joined = joiner.join(Map.of(new Query("q"),
                List.of(legs(leg("keyword", "zeta"), leg("vector", "alpha")))));
        assertThat(ids(joined)).containsExactly("zeta", "alpha");

        // Two keyword #1 hits from different queries: same score, rank and leg -> id order.
        Map<Query, List<List<Document>>> input = new LinkedHashMap<>();
        input.put(new Query("q1"), List.of(leg("keyword", "mike")));
        input.put(new Query("q2"), List.of(leg("keyword", "bravo")));
        assertThat(ids(joiner.join(input))).containsExactly("bravo", "mike");
    }

    @Test
    void orderDoesNotDependOnQueryIterationOrder() {
        Map<Query, List<List<Document>>> forward = new LinkedHashMap<>();
        forward.put(new Query("q1"), List.of(legs(leg("keyword", "a", "b", "c"), leg("vector", "d", "a"))));
        forward.put(new Query("q2"), List.of(legs(leg("keyword", "d", "c"), leg("vector", "b", "e"))));
        Map<Query, List<List<Document>>> reversed = new LinkedHashMap<>();
        reversed.put(new Query("q2"), forward.get(new Query("q2")));
        reversed.put(new Query("q1"), forward.get(new Query("q1")));

        assertThat(ids(joiner.join(reversed))).containsExactlyElementsOf(ids(joiner.join(forward)));
    }

    @Test
    void ranksComeFromLegRankNotListPosition() {
        // The retriever's list may interleave legs; the leg rank, not the position, is the rank.
        List<Document> shuffled = List.of(hit("v2", "vector", 2), hit("k1", "keyword", 1),
                hit("v1", "vector", 1), hit("k2", "keyword", 2));

        List<Document> joined = joiner.join(Map.of(new Query("q"), List.of(shuffled)));

        assertThat(ids(joined)).containsExactly("k1", "v1", "k2", "v2");
    }

    @Test
    void emptyInputAndEmptyLegsYieldNothing() {
        assertThat(joiner.join(Map.of())).isEmpty();
        assertThat(joiner.join(Map.of(new Query("q"), List.of(List.of())))).isEmpty();
        assertThat(ids(joiner.join(Map.of(new Query("q"), List.of(leg("vector", "only"))))))
                .containsExactly("only");
    }

    @Test
    void fusedDocumentCarriesProvenanceButNotPerHitBookkeeping() {
        Document keywordHit = new Document("a", "text a", Map.of(RagContextKeys.LEG, "keyword",
                RagContextKeys.LEG_RANK, 2, "keyword_score", 7.5, "author", "Martin"));
        Document vectorHit = new Document("a", "text a", Map.of(RagContextKeys.LEG, "vector",
                RagContextKeys.LEG_RANK, 1, "vector_score", 0.91, "author", "Martin"));

        Document fused = joiner.join(Map.of(new Query("q"), List.of(List.of(keywordHit, vectorHit)))).getFirst();

        assertThat(fused.getMetadata())
                .doesNotContainKeys(RagContextKeys.LEG, RagContextKeys.LEG_RANK)
                .containsEntry("author", "Martin")
                .containsEntry("keyword_rank", 2)
                .containsEntry("vector_rank", 1)
                .containsEntry("keyword_score", 7.5)
                .containsEntry("vector_score", 0.91)
                .containsKey("rrf_score");
        assertThat(fused.getScore()).isEqualTo(fused.getMetadata().get("rrf_score"));
        assertThat(fused.getText()).isEqualTo("text a");
    }

    @Test
    void untaggedListsAreRankedByPosition() {
        List<Document> untagged = List.of(new Document("u1", "x", Map.of()), new Document("u2", "y", Map.of()));

        assertThat(ids(joiner.join(Map.of(new Query("q"), List.of(untagged))))).containsExactly("u1", "u2");
    }

    @Test
    void rejectsANonPositiveCap() {
        assertThatThrownBy(() -> new RrfDocumentJoiner(60, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RrfDocumentJoiner(0, 20)).isInstanceOf(IllegalArgumentException.class);
    }
}
