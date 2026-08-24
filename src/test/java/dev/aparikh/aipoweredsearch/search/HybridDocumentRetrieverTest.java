package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HybridDocumentRetriever}.
 *
 * <p>These cover the contract Spring AI's RAG pipeline depends on: that hybrid search results are
 * faithfully translated into {@link Document} instances, that retrieval parameters reach the
 * repository, and that a retrieval failure degrades to "no context" rather than failing the
 * conversation.</p>
 */
@ExtendWith(MockitoExtension.class)
class HybridDocumentRetrieverTest {

    private static final String COLLECTION = "books";

    @Mock
    private SearchRepository searchRepository;

    private HybridDocumentRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = HybridDocumentRetriever.builder(searchRepository)
                .collection(COLLECTION)
                .topK(5)
                .similarityThreshold(0.3)
                .build();
    }

    // ==================== Document Mapping ====================

    @Nested
    class DocumentMapping {

        @Test
        void shouldMapSolrResultsToDocuments() {
            stubHybridSearch(List.of(
                    result("doc-1", "Spring Boot simplifies configuration.", 0.0328),
                    result("doc-2", "Solr supports dense vector search.", 0.0161)));

            List<Document> documents = retriever.retrieve(new Query("spring boot"));

            assertThat(documents).hasSize(2);
            assertThat(documents.get(0).getId()).isEqualTo("doc-1");
            assertThat(documents.get(0).getText()).isEqualTo("Spring Boot simplifies configuration.");
            assertThat(documents.get(1).getId()).isEqualTo("doc-2");
        }

        @Test
        void shouldUseFusedRrfScoreAsDocumentScore() {
            stubHybridSearch(List.of(result("doc-1", "content", 0.0328)));

            List<Document> documents = retriever.retrieve(new Query("anything"));

            assertThat(documents.get(0).getScore()).isEqualTo(0.0328);
        }

        @Test
        void shouldFallBackToPlainScoreWhenFusionWasBypassed() {
            // Hybrid search's keyword-only / vector-only cascade returns raw Solr hits with no
            // rrf_score. Those documents must still carry their score.
            Map<String, Object> fallbackHit = new LinkedHashMap<>();
            fallbackHit.put("id", "doc-1");
            fallbackHit.put("content", "content");
            fallbackHit.put("score", 7.5);
            stubHybridSearch(List.of(fallbackHit));

            List<Document> documents = retriever.retrieve(new Query("anything"));

            assertThat(documents.get(0).getScore()).isEqualTo(7.5);
        }

        @Test
        void shouldNotDuplicateScoreIntoMetadata() {
            // RrfMerger sets `score` as a copy of `rrf_score`; it is already the document's score.
            stubHybridSearch(List.of(result("doc-1", "content", 0.0328)));

            Map<String, Object> metadata = retriever.retrieve(new Query("anything")).get(0).getMetadata();

            assertThat(metadata).doesNotContainKey("score");
            assertThat(metadata).containsEntry("rrf_score", 0.0328);
        }

        @Test
        void shouldPreserveRrfProvenanceInMetadata() {
            Map<String, Object> hit = result("doc-1", "content", 0.0328);
            hit.put("keyword_rank", 1);
            hit.put("vector_rank", 3);
            hit.put("keyword_score", 8.2);
            hit.put("vector_score", 0.81);
            stubHybridSearch(List.of(hit));

            Map<String, Object> metadata = retriever.retrieve(new Query("anything")).get(0).getMetadata();

            // Retaining these is what lets a caller see which leg surfaced a piece of context.
            assertThat(metadata)
                    .containsEntry("keyword_rank", 1)
                    .containsEntry("vector_rank", 3)
                    .containsEntry("keyword_score", 8.2)
                    .containsEntry("vector_score", 0.81)
                    .containsEntry("rrf_score", 0.0328);
        }

        @Test
        void shouldCarryMetadataFieldsThrough() {
            Map<String, Object> hit = result("doc-1", "content", 0.03);
            hit.put("metadata_category", "AI");
            hit.put("metadata_year", 2024);
            stubHybridSearch(List.of(hit));

            Map<String, Object> metadata = retriever.retrieve(new Query("anything")).get(0).getMetadata();

            assertThat(metadata)
                    .containsEntry("metadata_category", "AI")
                    .containsEntry("metadata_year", 2024);
        }

        @Test
        void shouldExcludeRawVectorFromMetadata() {
            Map<String, Object> hit = result("doc-1", "content", 0.03);
            hit.put("vector", List.of(0.1f, 0.2f, 0.3f));
            hit.put("_version_", 1801234567890123456L);
            stubHybridSearch(List.of(hit));

            Map<String, Object> metadata = retriever.retrieve(new Query("anything")).get(0).getMetadata();

            // A 1536-dimension embedding in the prompt context would be pure payload bloat.
            assertThat(metadata).doesNotContainKeys("vector", "_version_", "id", "content");
        }

        @Test
        void shouldSkipDocumentsWithoutText() {
            Map<String, Object> blank = new LinkedHashMap<>();
            blank.put("id", "doc-empty");
            blank.put("rrf_score", 0.02);
            stubHybridSearch(List.of(result("doc-1", "real content", 0.03), blank));

            List<Document> documents = retriever.retrieve(new Query("anything"));

            assertThat(documents).hasSize(1);
            assertThat(documents.get(0).getId()).isEqualTo("doc-1");
        }

        @Test
        void shouldSkipDocumentsWithBlankText() {
            stubHybridSearch(List.of(result("doc-blank", "   ", 0.03)));

            assertThat(retriever.retrieve(new Query("anything"))).isEmpty();
        }
    }

    // ==================== Retrieval Parameters ====================

    @Nested
    class RetrievalParameters {

        @Test
        void shouldPassConfiguredParametersToRepository() {
            stubHybridSearch(List.of());

            HybridDocumentRetriever configured = HybridDocumentRetriever.builder(searchRepository)
                    .collection("manuals")
                    .topK(7)
                    .similarityThreshold(0.42)
                    .filterExpression("metadata_year:[2020 TO *]")
                    .build();

            configured.retrieve(new Query("how do I reset it"));

            ArgumentCaptor<String> collection = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Integer> topK = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> fields = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Double> minScore = ArgumentCaptor.forClass(Double.class);

            org.mockito.Mockito.verify(searchRepository).executeHybridRerankSearch(
                    collection.capture(), query.capture(), topK.capture(),
                    filter.capture(), fields.capture(), minScore.capture());

            assertThat(collection.getValue()).isEqualTo("manuals");
            assertThat(query.getValue()).isEqualTo("how do I reset it");
            assertThat(topK.getValue()).isEqualTo(7);
            assertThat(filter.getValue()).isEqualTo("metadata_year:[2020 TO *]");
            assertThat(minScore.getValue()).isEqualTo(0.42);
        }

        @Test
        void shouldNotRequestTheVectorFieldFromSolr() {
            stubHybridSearch(List.of());

            retriever.retrieve(new Query("anything"));

            ArgumentCaptor<String> fields = ArgumentCaptor.forClass(String.class);
            org.mockito.Mockito.verify(searchRepository).executeHybridRerankSearch(
                    anyString(), anyString(), anyInt(), any(), fields.capture(), any());

            assertThat(fields.getValue()).contains("id", "content", "metadata_*");
            assertThat(fields.getValue()).doesNotContain("vector");
        }

        @Test
        void shouldDefaultTopKWhenNotConfigured() {
            stubHybridSearch(List.of());

            HybridDocumentRetriever defaults = HybridDocumentRetriever.builder(searchRepository)
                    .collection(COLLECTION)
                    .build();
            defaults.retrieve(new Query("anything"));

            ArgumentCaptor<Integer> topK = ArgumentCaptor.forClass(Integer.class);
            org.mockito.Mockito.verify(searchRepository).executeHybridRerankSearch(
                    anyString(), anyString(), topK.capture(), any(), any(), any());

            assertThat(topK.getValue()).isEqualTo(HybridDocumentRetriever.DEFAULT_TOP_K);
        }
    }

    // ==================== Failure Handling ====================

    @Nested
    class FailureHandling {

        @Test
        void shouldReturnEmptyListWhenNoResults() {
            stubHybridSearch(List.of());

            assertThat(retriever.retrieve(new Query("nothing matches"))).isEmpty();
        }

        @Test
        void shouldReturnEmptyListWhenResponseIsNull() {
            when(searchRepository.executeHybridRerankSearch(
                    anyString(), anyString(), anyInt(), any(), any(), any()))
                    .thenReturn(null);

            assertThat(retriever.retrieve(new Query("anything"))).isEmpty();
        }

        @Test
        void shouldDegradeToNoContextWhenSearchFails() {
            when(searchRepository.executeHybridRerankSearch(
                    anyString(), anyString(), anyInt(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Solr unavailable"));

            // The conversation should continue from chat memory rather than erroring out.
            assertThat(retriever.retrieve(new Query("anything"))).isEmpty();
        }

        @Test
        void shouldRejectNullQuery() {
            assertThatThrownBy(() -> retriever.retrieve(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void blankQueryTextCannotReachTheRetriever() {
            // Documents why retrieve() carries no blank-text guard: Spring AI's Query asserts
            // non-blank text in its constructor, so no Query instance can hold blank text. Blank
            // input is rejected at the API boundary instead (see AskRequest's @NotBlank).
            assertThatThrownBy(() -> new Query("   "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================== Builder Validation ====================

    @Nested
    class BuilderValidation {

        @Test
        void shouldRequireCollection() {
            assertThatThrownBy(() -> HybridDocumentRetriever.builder(searchRepository).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("collection");
        }

        @Test
        void shouldRequireRepository() {
            assertThatThrownBy(() -> HybridDocumentRetriever.builder(null).collection(COLLECTION).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("searchRepository");
        }

        @Test
        void shouldRejectNonPositiveTopK() {
            assertThatThrownBy(() -> HybridDocumentRetriever.builder(searchRepository)
                    .collection(COLLECTION)
                    .topK(0)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("topK");
        }

        @Test
        void shouldRejectSimilarityThresholdOutsideUnitRange() {
            // minScore is a cosine similarity, so anything outside [0..1] is a caller error rather
            // than something to silently accept.
            assertThatThrownBy(() -> HybridDocumentRetriever.builder(searchRepository)
                    .collection(COLLECTION)
                    .similarityThreshold(1.5)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("similarityThreshold");
        }

        @Test
        void shouldAllowNullSimilarityThreshold() {
            HybridDocumentRetriever built = HybridDocumentRetriever.builder(searchRepository)
                    .collection(COLLECTION)
                    .similarityThreshold(null)
                    .build();

            assertThat(built).isNotNull();
        }
    }

    // ==================== Helpers ====================

    private void stubHybridSearch(List<Map<String, Object>> documents) {
        when(searchRepository.executeHybridRerankSearch(
                anyString(), anyString(), anyInt(), any(), any(), any()))
                .thenReturn(new SearchResponse(documents, Map.of(), Map.of(), null));
    }

    private Map<String, Object> result(String id, String content, double rrfScore) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", id);
        document.put("content", content);
        document.put("rrf_score", rrfScore);
        document.put("score", rrfScore);
        return document;
    }
}
