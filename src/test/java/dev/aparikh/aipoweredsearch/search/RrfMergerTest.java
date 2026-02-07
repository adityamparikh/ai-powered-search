package dev.aparikh.aipoweredsearch.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive unit tests for {@link RrfMerger}.
 */
class RrfMergerTest {

    private RrfMerger merger;

    @BeforeEach
    void setUp() {
        merger = new RrfMerger();
    }

    // ==================== Construction ====================

    @Nested
    class Construction {

        @Test
        void shouldCreateWithDefaultK() {
            RrfMerger m = new RrfMerger();
            assertEquals(RrfMerger.DEFAULT_K, m.getK());
        }

        @Test
        void shouldCreateWithCustomK() {
            RrfMerger m = new RrfMerger(30);
            assertEquals(30, m.getK());
        }

        @Test
        void shouldRejectZeroK() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> new RrfMerger(0));
            assertTrue(ex.getMessage().contains("positive"));
        }

        @Test
        void shouldRejectNegativeK() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> new RrfMerger(-5));
            assertTrue(ex.getMessage().contains("positive"));
        }

        @Test
        void shouldAcceptKOfOne() {
            RrfMerger m = new RrfMerger(1);
            assertEquals(1, m.getK());
        }
    }

    // ==================== Empty / Null Inputs ====================

    @Nested
    class EmptyAndNullInputs {

        @Test
        void shouldReturnEmptyForBothNull() {
            List<Map<String, Object>> result = merger.merge(null, null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        void shouldReturnEmptyForBothEmpty() {
            List<Map<String, Object>> result = merger.merge(List.of(), List.of());
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        void shouldReturnEmptyForNullKeywordAndEmptyVector() {
            List<Map<String, Object>> result = merger.merge(null, List.of());
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        void shouldReturnEmptyForEmptyKeywordAndNullVector() {
            List<Map<String, Object>> result = merger.merge(List.of(), null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    // ==================== Single-Source Results ====================

    @Nested
    class SingleSourceResults {

        @Test
        void shouldHandleKeywordResultsOnly() {
            List<Map<String, Object>> keyword = List.of(
                    doc("1", 5.0),
                    doc("2", 3.0)
            );

            List<Map<String, Object>> result = merger.merge(keyword, List.of());

            assertEquals(2, result.size());
            assertEquals("1", result.get(0).get("id"));
            assertEquals("2", result.get(1).get("id"));

            // Only keyword rank/score should be present
            assertNotNull(result.get(0).get(RrfMerger.KEYWORD_SCORE_FIELD));
            assertNotNull(result.get(0).get(RrfMerger.KEYWORD_RANK_FIELD));
            assertEquals(1, result.get(0).get(RrfMerger.KEYWORD_RANK_FIELD));
            assertFalse(result.get(0).containsKey(RrfMerger.VECTOR_SCORE_FIELD));
            assertFalse(result.get(0).containsKey(RrfMerger.VECTOR_RANK_FIELD));
        }

        @Test
        void shouldHandleVectorResultsOnly() {
            List<Map<String, Object>> vector = List.of(
                    doc("A", 0.95),
                    doc("B", 0.80)
            );

            List<Map<String, Object>> result = merger.merge(List.of(), vector);

            assertEquals(2, result.size());
            assertEquals("A", result.get(0).get("id"));
            assertEquals("B", result.get(1).get("id"));

            // Only vector rank/score should be present
            assertNotNull(result.get(0).get(RrfMerger.VECTOR_SCORE_FIELD));
            assertNotNull(result.get(0).get(RrfMerger.VECTOR_RANK_FIELD));
            assertEquals(1, result.get(0).get(RrfMerger.VECTOR_RANK_FIELD));
            assertFalse(result.get(0).containsKey(RrfMerger.KEYWORD_SCORE_FIELD));
            assertFalse(result.get(0).containsKey(RrfMerger.KEYWORD_RANK_FIELD));
        }

        @Test
        void shouldHandleKeywordResultsWithNullVector() {
            List<Map<String, Object>> keyword = List.of(doc("1", 5.0));

            List<Map<String, Object>> result = merger.merge(keyword, null);

            assertEquals(1, result.size());
            assertEquals("1", result.get(0).get("id"));
        }

        @Test
        void shouldHandleVectorResultsWithNullKeyword() {
            List<Map<String, Object>> vector = List.of(doc("1", 0.9));

            List<Map<String, Object>> result = merger.merge(null, vector);

            assertEquals(1, result.size());
            assertEquals("1", result.get(0).get("id"));
        }
    }

    // ==================== RRF Score Computation ====================

    @Nested
    class RrfScoreComputation {

        @Test
        void shouldComputeCorrectRrfScoreForKeywordOnly() {
            // With default k=60, rank 1 document: score = 1/(60+1) = 0.016393...
            List<Map<String, Object>> keyword = List.of(doc("1", 10.0));

            List<Map<String, Object>> result = merger.merge(keyword, List.of());

            double expectedRrf = 1.0 / (60 + 1);
            assertEquals(expectedRrf, (Double) result.get(0).get(RrfMerger.RRF_SCORE_FIELD), 1e-10);
        }

        @Test
        void shouldComputeCorrectRrfScoreForDocumentInBothLists() {
            // Document at rank 1 in keyword and rank 1 in vector
            // RRF = 1/(60+1) + 1/(60+1) = 2 * 0.016393...
            List<Map<String, Object>> keyword = List.of(doc("1", 10.0));
            List<Map<String, Object>> vector = List.of(doc("1", 0.95));

            List<Map<String, Object>> result = merger.merge(keyword, vector);

            double expectedRrf = 1.0 / (60 + 1) + 1.0 / (60 + 1);
            assertEquals(1, result.size());
            assertEquals(expectedRrf, (Double) result.get(0).get(RrfMerger.RRF_SCORE_FIELD), 1e-10);
        }

        @Test
        void shouldComputeCorrectRrfScoreForDifferentRanks() {
            // Document at rank 3 in keyword and rank 5 in vector
            // RRF = 1/(60+3) + 1/(60+5) = 1/63 + 1/65
            List<Map<String, Object>> keyword = List.of(
                    doc("A", 10.0), doc("B", 8.0), doc("target", 6.0));
            List<Map<String, Object>> vector = List.of(
                    doc("X", 0.99), doc("Y", 0.95), doc("Z", 0.90),
                    doc("W", 0.85), doc("target", 0.80));

            List<Map<String, Object>> result = merger.merge(keyword, vector);

            // Find the target document
            Map<String, Object> target = result.stream()
                    .filter(d -> "target".equals(d.get("id")))
                    .findFirst().orElseThrow();

            double expectedRrf = 1.0 / (60 + 3) + 1.0 / (60 + 5);
            assertEquals(expectedRrf, (Double) target.get(RrfMerger.RRF_SCORE_FIELD), 1e-10);
            assertEquals(3, target.get(RrfMerger.KEYWORD_RANK_FIELD));
            assertEquals(5, target.get(RrfMerger.VECTOR_RANK_FIELD));
        }

        @Test
        void shouldComputeCorrectScoresWithCustomK() {
            RrfMerger customMerger = new RrfMerger(10);

            List<Map<String, Object>> keyword = List.of(doc("1", 5.0));
            List<Map<String, Object>> vector = List.of(doc("1", 0.9));

            List<Map<String, Object>> result = customMerger.merge(keyword, vector);

            // k=10: RRF = 1/(10+1) + 1/(10+1) = 2/11
            double expectedRrf = 2.0 / 11;
            assertEquals(expectedRrf, (Double) result.get(0).get(RrfMerger.RRF_SCORE_FIELD), 1e-10);
        }

        @Test
        void shouldSetScoreFieldToRrfScore() {
            List<Map<String, Object>> keyword = List.of(doc("1", 10.0));

            List<Map<String, Object>> result = merger.merge(keyword, List.of());

            // The "score" field should equal the RRF score, not the original
            assertEquals(result.get(0).get(RrfMerger.RRF_SCORE_FIELD), result.get(0).get(RrfMerger.SCORE_FIELD));
        }
    }

    // ==================== Sorting ====================

    @Nested
    class Sorting {

        @Test
        void shouldSortByRrfScoreDescending() {
            // Doc A: in both lists at rank 1 → highest RRF
            // Doc B: only in keyword at rank 2 → lower RRF
            // Doc C: only in vector at rank 2 → lower RRF
            List<Map<String, Object>> keyword = List.of(doc("A", 10.0), doc("B", 8.0));
            List<Map<String, Object>> vector = List.of(doc("A", 0.95), doc("C", 0.85));

            List<Map<String, Object>> result = merger.merge(keyword, vector);

            assertEquals(3, result.size());
            // A should be first (in both lists)
            assertEquals("A", result.get(0).get("id"));

            // Verify descending order
            for (int i = 0; i < result.size() - 1; i++) {
                double score1 = (Double) result.get(i).get(RrfMerger.RRF_SCORE_FIELD);
                double score2 = (Double) result.get(i + 1).get(RrfMerger.RRF_SCORE_FIELD);
                assertTrue(score1 >= score2,
                        "Results should be sorted descending by RRF score: " + score1 + " >= " + score2);
            }
        }

        @Test
        void shouldBoostDocumentsAppearingInBothLists() {
            // Document in both lists should rank higher than documents in only one
            List<Map<String, Object>> keyword = List.of(doc("both", 5.0), doc("kw-only", 10.0));
            List<Map<String, Object>> vector = List.of(doc("both", 0.5), doc("vec-only", 0.99));

            List<Map<String, Object>> result = merger.merge(keyword, vector);

            // "both" appears at rank 1 in keyword and rank 1 in vector
            // "kw-only" at rank 2 in keyword only
            // "vec-only" at rank 2 in vector only
            assertEquals("both", result.get(0).get("id"),
                    "Document in both lists should rank highest");
        }
    }

    // ==================== Field Merging ====================

    @Nested
    class FieldMerging {

        @Test
        void shouldPreferVectorFieldsOnConflict() {
            Map<String, Object> kwDoc = new HashMap<>();
            kwDoc.put("id", "1");
            kwDoc.put("title", "Keyword Title");
            kwDoc.put("score", 5.0);

            Map<String, Object> vecDoc = new HashMap<>();
            vecDoc.put("id", "1");
            vecDoc.put("title", "Vector Title");
            vecDoc.put("score", 0.95);

            List<Map<String, Object>> result = merger.merge(List.of(kwDoc), List.of(vecDoc));

            assertEquals(1, result.size());
            assertEquals("Vector Title", result.get(0).get("title"),
                    "Vector field values should take precedence on conflict");
        }

        @Test
        void shouldPreserveFieldsFromBothSources() {
            Map<String, Object> kwDoc = new HashMap<>();
            kwDoc.put("id", "1");
            kwDoc.put("keyword_field", "from_keyword");
            kwDoc.put("score", 5.0);

            Map<String, Object> vecDoc = new HashMap<>();
            vecDoc.put("id", "1");
            vecDoc.put("vector_field", "from_vector");
            vecDoc.put("score", 0.9);

            List<Map<String, Object>> result = merger.merge(List.of(kwDoc), List.of(vecDoc));

            assertEquals(1, result.size());
            assertEquals("from_keyword", result.get(0).get("keyword_field"));
            assertEquals("from_vector", result.get(0).get("vector_field"));
        }

        @Test
        void shouldNeverOverwriteId() {
            Map<String, Object> kwDoc = new HashMap<>();
            kwDoc.put("id", "1");
            kwDoc.put("score", 5.0);

            Map<String, Object> vecDoc = new HashMap<>();
            vecDoc.put("id", "1");
            vecDoc.put("score", 0.9);

            List<Map<String, Object>> result = merger.merge(List.of(kwDoc), List.of(vecDoc));

            assertEquals("1", result.get(0).get("id"));
        }

        @Test
        void shouldNotMutateInputMaps() {
            Map<String, Object> kwDoc = new HashMap<>();
            kwDoc.put("id", "1");
            kwDoc.put("title", "Original");
            kwDoc.put("score", 5.0);

            Map<String, Object> vecDoc = new HashMap<>();
            vecDoc.put("id", "1");
            vecDoc.put("title", "Changed");
            vecDoc.put("score", 0.9);

            merger.merge(List.of(kwDoc), List.of(vecDoc));

            // Original maps should be unchanged
            assertEquals("Original", kwDoc.get("title"));
            assertEquals("Changed", vecDoc.get("title"));
            assertFalse(kwDoc.containsKey(RrfMerger.RRF_SCORE_FIELD));
            assertFalse(vecDoc.containsKey(RrfMerger.RRF_SCORE_FIELD));
        }
    }

    // ==================== Score Preservation ====================

    @Nested
    class ScorePreservation {

        @Test
        void shouldPreserveOriginalKeywordScore() {
            List<Map<String, Object>> keyword = List.of(doc("1", 7.5));

            List<Map<String, Object>> result = merger.merge(keyword, List.of());

            assertEquals(7.5, (Double) result.get(0).get(RrfMerger.KEYWORD_SCORE_FIELD));
        }

        @Test
        void shouldPreserveOriginalVectorScore() {
            List<Map<String, Object>> vector = List.of(doc("1", 0.88));

            List<Map<String, Object>> result = merger.merge(List.of(), vector);

            assertEquals(0.88, (Double) result.get(0).get(RrfMerger.VECTOR_SCORE_FIELD));
        }

        @Test
        void shouldPreserveBothOriginalScores() {
            List<Map<String, Object>> keyword = List.of(doc("1", 7.5));
            List<Map<String, Object>> vector = List.of(doc("1", 0.88));

            List<Map<String, Object>> result = merger.merge(keyword, vector);

            assertEquals(7.5, (Double) result.get(0).get(RrfMerger.KEYWORD_SCORE_FIELD));
            assertEquals(0.88, (Double) result.get(0).get(RrfMerger.VECTOR_SCORE_FIELD));
        }

        @Test
        void shouldHandleDocumentWithoutScore() {
            Map<String, Object> kwDoc = new HashMap<>();
            kwDoc.put("id", "1");
            // No score field

            List<Map<String, Object>> result = merger.merge(List.of(kwDoc), List.of());

            assertEquals(1, result.size());
            assertFalse(result.get(0).containsKey(RrfMerger.KEYWORD_SCORE_FIELD),
                    "Should not add keyword_score when original score is null");
        }

        @Test
        void shouldHandleNonNumericScore() {
            Map<String, Object> kwDoc = new HashMap<>();
            kwDoc.put("id", "1");
            kwDoc.put("score", "not-a-number");

            List<Map<String, Object>> result = merger.merge(List.of(kwDoc), List.of());

            assertEquals(1, result.size());
            assertFalse(result.get(0).containsKey(RrfMerger.KEYWORD_SCORE_FIELD));
        }

        @Test
        void shouldHandleFloatScore() {
            Map<String, Object> kwDoc = new HashMap<>();
            kwDoc.put("id", "1");
            kwDoc.put("score", 3.14f);

            List<Map<String, Object>> result = merger.merge(List.of(kwDoc), List.of());

            assertEquals(3.14, (Double) result.get(0).get(RrfMerger.KEYWORD_SCORE_FIELD), 0.01);
        }

        @Test
        void shouldHandleIntegerScore() {
            Map<String, Object> kwDoc = new HashMap<>();
            kwDoc.put("id", "1");
            kwDoc.put("score", 42);

            List<Map<String, Object>> result = merger.merge(List.of(kwDoc), List.of());

            assertEquals(42.0, (Double) result.get(0).get(RrfMerger.KEYWORD_SCORE_FIELD));
        }
    }

    // ==================== Document ID Handling ====================

    @Nested
    class DocumentIdHandling {

        @Test
        void shouldThrowForMissingId() {
            Map<String, Object> doc = new HashMap<>();
            doc.put("title", "No ID");

            assertThrows(IllegalArgumentException.class,
                    () -> merger.merge(List.of(doc), List.of()));
        }

        @Test
        void shouldHandleNumericId() {
            Map<String, Object> kwDoc = new HashMap<>();
            kwDoc.put("id", 123);
            kwDoc.put("score", 5.0);

            List<Map<String, Object>> result = merger.merge(List.of(kwDoc), List.of());

            assertEquals(1, result.size());
            // ID is converted to string internally for matching
            assertEquals(123, result.get(0).get("id")); // Original value preserved
        }

        @Test
        void shouldMatchDocumentsByIdAcrossLists() {
            Map<String, Object> kwDoc = new HashMap<>();
            kwDoc.put("id", "doc-1");
            kwDoc.put("score", 5.0);

            Map<String, Object> vecDoc = new HashMap<>();
            vecDoc.put("id", "doc-1");
            vecDoc.put("score", 0.9);

            List<Map<String, Object>> result = merger.merge(List.of(kwDoc), List.of(vecDoc));

            // Same ID should produce a single merged document
            assertEquals(1, result.size());
        }

        @Test
        void shouldTreatDifferentIdsAsSeparateDocuments() {
            List<Map<String, Object>> keyword = List.of(doc("1", 5.0));
            List<Map<String, Object>> vector = List.of(doc("2", 0.9));

            List<Map<String, Object>> result = merger.merge(keyword, vector);

            assertEquals(2, result.size());
        }
    }

    // ==================== Large Result Sets ====================

    @Nested
    class LargeResultSets {

        @Test
        void shouldHandleLargeNumberOfDocuments() {
            int size = 500;
            List<Map<String, Object>> keyword = new ArrayList<>();
            List<Map<String, Object>> vector = new ArrayList<>();

            // Add overlapping documents first so they get high ranks (1-50) in both lists.
            // Being top-ranked in both lists gives a higher RRF score than being top-ranked in only one.
            for (int i = 0; i < 50; i++) {
                keyword.add(doc("both-" + i, 10.0 - i * 0.01));
                vector.add(doc("both-" + i, 0.99 - i * 0.001));
            }

            for (int i = 0; i < size; i++) {
                keyword.add(doc("kw-" + i, 8.0 - i * 0.01));
                vector.add(doc("vec-" + i, 0.90 - i * 0.001));
            }

            List<Map<String, Object>> result = merger.merge(keyword, vector);

            // 500 keyword-only + 500 vector-only + 50 shared = 1050
            assertEquals(1050, result.size());

            // Shared documents should be ranked highest (they appear at top ranks in both lists)
            for (int i = 0; i < 50; i++) {
                String topId = (String) result.get(i).get("id");
                assertTrue(topId.startsWith("both-"),
                        "Top results should be documents appearing in both lists, got: " + topId);
            }
        }

        @Test
        void midRankedDocInBothListsShouldBeatHigherRankedDocInOneList() {
            // "both-" docs at ranks 80-89 in both lists.
            // "kw-" docs at ranks 1-79 in keyword only.
            // A doc at rank 80 in both lists: 2 * 1/(60+80) = 2 * 0.00714 = 0.01429
            // A doc at rank 50 in one list:    1/(60+50)     = 0.00909
            // So a mid-ranked dual-list doc should beat a moderately-ranked single-list doc.
            List<Map<String, Object>> keyword = new ArrayList<>();
            List<Map<String, Object>> vector = new ArrayList<>();

            for (int i = 0; i < 79; i++) {
                keyword.add(doc("kw-" + i, 10.0 - i * 0.01));
                vector.add(doc("vec-" + i, 0.99 - i * 0.001));
            }
            for (int i = 0; i < 10; i++) {
                keyword.add(doc("both-" + i, 5.0 - i * 0.01));
                vector.add(doc("both-" + i, 0.50 - i * 0.001));
            }

            List<Map<String, Object>> result = merger.merge(keyword, vector);

            // Find best single-list and best dual-list scores
            double bestDualScore = result.stream()
                    .filter(d -> ((String) d.get("id")).startsWith("both-"))
                    .mapToDouble(d -> ((Number) d.get(RrfMerger.RRF_SCORE_FIELD)).doubleValue())
                    .max().orElseThrow();
            double worstSingleInTop50 = result.stream()
                    .filter(d -> !((String) d.get("id")).startsWith("both-"))
                    .mapToDouble(d -> ((Number) d.get(RrfMerger.RRF_SCORE_FIELD)).doubleValue())
                    .sorted().toArray()[50 - 1]; // 50th best single-list doc

            assertTrue(bestDualScore > worstSingleInTop50,
                    "Mid-ranked dual-list doc should beat a moderately-ranked single-list doc");
        }

        @Test
        void shouldMaintainDescendingSortForLargeResults() {
            List<Map<String, Object>> keyword = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                keyword.add(doc("doc-" + i, 10.0 - i * 0.1));
            }

            List<Map<String, Object>> result = merger.merge(keyword, List.of());

            for (int i = 0; i < result.size() - 1; i++) {
                double s1 = (Double) result.get(i).get(RrfMerger.RRF_SCORE_FIELD);
                double s2 = (Double) result.get(i + 1).get(RrfMerger.RRF_SCORE_FIELD);
                assertTrue(s1 >= s2, "Result at position " + i + " should have score >= position " + (i + 1));
            }
        }
    }

    // ==================== Helper Methods ====================

    @Nested
    class HelperMethods {

        @Test
        void shouldExtractDocIdFromString() {
            assertEquals("abc", merger.extractDocId(Map.of("id", "abc")));
        }

        @Test
        void shouldExtractDocIdFromNumber() {
            assertEquals("42", merger.extractDocId(Map.of("id", 42)));
        }

        @Test
        void shouldThrowForMissingDocId() {
            assertThrows(IllegalArgumentException.class,
                    () -> merger.extractDocId(Map.of("title", "no id")));
        }

        @Test
        void shouldExtractScoreFromDouble() {
            assertEquals(3.14, merger.extractScore(Map.of("score", 3.14)));
        }

        @Test
        void shouldExtractScoreFromFloat() {
            assertEquals(3.14, merger.extractScore(Map.of("score", 3.14f)), 0.01);
        }

        @Test
        void shouldExtractScoreFromInteger() {
            assertEquals(42.0, merger.extractScore(Map.of("score", 42)));
        }

        @Test
        void shouldReturnNullForMissingScore() {
            assertNull(merger.extractScore(Map.of("id", "1")));
        }

        @Test
        void shouldReturnNullForNonNumericScore() {
            assertNull(merger.extractScore(Map.of("score", "not-a-number")));
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    class EdgeCases {

        @Test
        void shouldHandleSingleDocumentInBothLists() {
            List<Map<String, Object>> keyword = List.of(doc("only", 5.0));
            List<Map<String, Object>> vector = List.of(doc("only", 0.9));

            List<Map<String, Object>> result = merger.merge(keyword, vector);

            assertEquals(1, result.size());
            assertEquals("only", result.get(0).get("id"));
            assertTrue(result.get(0).containsKey(RrfMerger.KEYWORD_SCORE_FIELD));
            assertTrue(result.get(0).containsKey(RrfMerger.VECTOR_SCORE_FIELD));
            assertTrue(result.get(0).containsKey(RrfMerger.KEYWORD_RANK_FIELD));
            assertTrue(result.get(0).containsKey(RrfMerger.VECTOR_RANK_FIELD));
        }

        @Test
        void shouldHandleDocumentsWithExtraFields() {
            Map<String, Object> kwDoc = new HashMap<>();
            kwDoc.put("id", "1");
            kwDoc.put("score", 5.0);
            kwDoc.put("title", "Test");
            kwDoc.put("author", "Jane");
            kwDoc.put("tags", List.of("java", "spring"));

            List<Map<String, Object>> result = merger.merge(List.of(kwDoc), List.of());

            assertEquals("Test", result.get(0).get("title"));
            assertEquals("Jane", result.get(0).get("author"));
            assertEquals(List.of("java", "spring"), result.get(0).get("tags"));
        }

        @Test
        void shouldHandleDocumentsWithNullFieldValues() {
            Map<String, Object> kwDoc = new HashMap<>();
            kwDoc.put("id", "1");
            kwDoc.put("score", 5.0);
            kwDoc.put("title", null);

            List<Map<String, Object>> result = assertDoesNotThrow(
                    () -> merger.merge(List.of(kwDoc), List.of()));

            assertEquals(1, result.size());
        }

        @Test
        void shouldReturnNewListInstance() {
            List<Map<String, Object>> keyword = List.of(doc("1", 5.0));

            List<Map<String, Object>> result1 = merger.merge(keyword, List.of());
            List<Map<String, Object>> result2 = merger.merge(keyword, List.of());

            assertNotSame(result1, result2, "Each merge should return a new list");
        }

        @Test
        void shouldProduceDeterministicResultsForSameInput() {
            List<Map<String, Object>> keyword = List.of(doc("A", 10.0), doc("B", 8.0));
            List<Map<String, Object>> vector = List.of(doc("B", 0.95), doc("C", 0.85));

            List<Map<String, Object>> result1 = merger.merge(keyword, vector);
            List<Map<String, Object>> result2 = merger.merge(keyword, vector);

            assertEquals(result1.size(), result2.size());
            for (int i = 0; i < result1.size(); i++) {
                assertEquals(result1.get(i).get("id"), result2.get(i).get("id"));
                assertEquals(result1.get(i).get(RrfMerger.RRF_SCORE_FIELD),
                        result2.get(i).get(RrfMerger.RRF_SCORE_FIELD));
            }
        }

        @Test
        void shouldHandleImmutableInputLists() {
            List<Map<String, Object>> keyword = Collections.unmodifiableList(List.of(doc("1", 5.0)));
            List<Map<String, Object>> vector = Collections.unmodifiableList(List.of(doc("2", 0.9)));

            List<Map<String, Object>> result = assertDoesNotThrow(
                    () -> merger.merge(keyword, vector));

            assertEquals(2, result.size());
        }
    }

    // ==================== MergedDocument Inner Class ====================

    @Nested
    class MergedDocumentTest {

        @Test
        void shouldTrackKeywordAndVectorScoresSeparately() {
            RrfMerger.MergedDocument merged = new RrfMerger.MergedDocument("1", new HashMap<>(Map.of("id", "1")));
            merged.addKeywordScore(0.016, 1, 10.0);
            merged.addVectorScore(0.015, 2, 0.95);

            assertEquals(0.031, merged.rrfScore, 1e-10);
            assertEquals(10.0, merged.keywordOriginalScore);
            assertEquals(0.95, merged.vectorOriginalScore);
            assertEquals(1, merged.keywordRank);
            assertEquals(2, merged.vectorRank);
        }

        @Test
        void shouldMergeVectorFieldsWithPrecedence() {
            Map<String, Object> initialFields = new HashMap<>();
            initialFields.put("id", "1");
            initialFields.put("shared_field", "keyword_value");
            initialFields.put("kw_only", "keyword_data");

            RrfMerger.MergedDocument merged = new RrfMerger.MergedDocument("1", initialFields);

            Map<String, Object> vectorDoc = new HashMap<>();
            vectorDoc.put("id", "1");
            vectorDoc.put("shared_field", "vector_value");
            vectorDoc.put("vec_only", "vector_data");
            vectorDoc.put("score", 0.9);

            merged.mergeVectorFields(vectorDoc);

            Map<String, Object> result = merged.toDocument();
            assertEquals("vector_value", result.get("shared_field"), "Vector value should overwrite keyword value");
            assertEquals("keyword_data", result.get("kw_only"), "Keyword-only fields should be preserved");
            assertEquals("vector_data", result.get("vec_only"), "Vector-only fields should be added");
            assertEquals("1", result.get("id"), "ID should not be overwritten");
        }

        @Test
        void shouldProduceCompleteOutputDocument() {
            RrfMerger.MergedDocument merged = new RrfMerger.MergedDocument("1",
                    new HashMap<>(Map.of("id", "1", "title", "Test")));
            merged.addKeywordScore(0.016, 1, 10.0);
            merged.addVectorScore(0.015, 2, 0.95);

            Map<String, Object> doc = merged.toDocument();

            assertEquals("1", doc.get("id"));
            assertEquals("Test", doc.get("title"));
            assertEquals(0.031, (Double) doc.get(RrfMerger.RRF_SCORE_FIELD), 1e-10);
            assertEquals(0.031, (Double) doc.get(RrfMerger.SCORE_FIELD), 1e-10);
            assertEquals(10.0, doc.get(RrfMerger.KEYWORD_SCORE_FIELD));
            assertEquals(0.95, doc.get(RrfMerger.VECTOR_SCORE_FIELD));
            assertEquals(1, doc.get(RrfMerger.KEYWORD_RANK_FIELD));
            assertEquals(2, doc.get(RrfMerger.VECTOR_RANK_FIELD));
        }
    }

    // ==================== Test Helpers ====================

    /**
     * Creates a simple document map with an id and score.
     */
    private static Map<String, Object> doc(String id, double score) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", id);
        doc.put("score", score);
        return doc;
    }
}
