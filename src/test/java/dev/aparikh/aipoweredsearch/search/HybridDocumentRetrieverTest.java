package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import dev.aparikh.aipoweredsearch.search.rag.RagContextKeys;
import dev.aparikh.aipoweredsearch.search.rag.RagObservations;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HybridDocumentRetriever}, the adapter that lets Spring AI's
 * modular RAG pipeline retrieve documents via hybrid (keyword + vector, RRF-fused) search.
 */
@ExtendWith(MockitoExtension.class)
class HybridDocumentRetrieverTest {

    private static final String COLLECTION = "books";
    private static final int TOP_K = 5;

    @Mock
    private SearchRepository searchRepository;

    private final TestObservationRegistry observationRegistry = TestObservationRegistry.create();

    private HybridDocumentRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new HybridDocumentRetriever(searchRepository, COLLECTION, TOP_K, observationRegistry);
    }

    private static Map<String, Object> solrDoc(String id, String content, Map<String, Object> extras) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", id);
        if (content != null) {
            doc.put("content", content);
        }
        doc.putAll(extras);
        return doc;
    }

    private void stubHybridResults(List<Map<String, Object>> documents) {
        when(searchRepository.executeHybridRerankSearch(
                any(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(new SearchResponse(documents, Map.of(), Map.of(), null));
    }

    @Test
    void convertsHybridResultsToSpringAiDocuments() {
        stubHybridResults(List.of(
                solrDoc("doc-1", "Spring Boot binds configuration properties", Map.of()),
                solrDoc("doc-2", "Validation with jakarta annotations", Map.of())));

        List<Document> documents = retriever.retrieve(Query.builder().text("how do I bind config?").build());

        assertThat(documents).hasSize(2);
        assertThat(documents.get(0).getId()).isEqualTo("doc-1");
        assertThat(documents.get(0).getText()).isEqualTo("Spring Boot binds configuration properties");
        assertThat(documents.get(1).getId()).isEqualTo("doc-2");
    }

    @Test
    void stripsMetadataPrefixFromSolrFields() {
        stubHybridResults(List.of(
                solrDoc("doc-1", "content", Map.of("metadata_author", "Craig", "metadata_year", 2026))));

        List<Document> documents = retriever.retrieve(Query.builder().text("q").build());

        assertThat(documents.get(0).getMetadata())
                .containsEntry("author", "Craig")
                .containsEntry("year", 2026)
                .doesNotContainKey("metadata_author");
    }

    @Test
    void carriesRrfProvenanceIntoDocumentMetadata() {
        stubHybridResults(List.of(
                solrDoc("doc-1", "content", Map.of(
                        "rrf_score", 0.032,
                        "keyword_rank", 1,
                        "vector_rank", 2,
                        "keyword_score", 8.4,
                        "vector_score", 0.91))));

        List<Document> documents = retriever.retrieve(Query.builder().text("q").build());

        // RRF discards raw scores by construction; keeping the provenance is what makes
        // "why was this chunk in the prompt?" answerable after the fact.
        assertThat(documents.get(0).getMetadata())
                .containsEntry("rrf_score", 0.032)
                .containsEntry("keyword_rank", 1)
                .containsEntry("vector_rank", 2);
    }

    @Test
    void preservesRrfRankingOrder() {
        // The repository returns documents already ordered by fused RRF score.
        stubHybridResults(List.of(
                solrDoc("best", "a", Map.of()),
                solrDoc("middle", "b", Map.of()),
                solrDoc("worst", "c", Map.of())));

        List<Document> documents = retriever.retrieve(Query.builder().text("q").build());

        assertThat(documents).extracting(Document::getId)
                .containsExactly("best", "middle", "worst");
    }

    @Test
    void requestsOnlyProjectableFieldsSoTheVectorIsNotFetched() {
        stubHybridResults(List.of());

        retriever.retrieve(Query.builder().text("q").build());

        ArgumentCaptor<String> fieldsCaptor = ArgumentCaptor.forClass(String.class);
        verify(searchRepository).executeHybridRerankSearch(
                eq(COLLECTION), eq("q"), eq(TOP_K), any(), fieldsCaptor.capture(), any(), any());

        // A null/"*" field list makes Solr return the 1536-dim vector field on every hit.
        assertThat(fieldsCaptor.getValue()).isNotNull();
        assertThat(fieldsCaptor.getValue()).doesNotContain("*,");
        assertThat(fieldsCaptor.getValue()).contains("id", "content", "metadata_*");
    }

    @Test
    void passesQueryTextCollectionAndTopKToTheRepository() {
        stubHybridResults(List.of());

        retriever.retrieve(Query.builder().text("machine learning frameworks").build());

        verify(searchRepository).executeHybridRerankSearch(
                eq(COLLECTION), eq("machine learning frameworks"), eq(TOP_K), any(), any(), any(), any());
    }

    @Test
    void returnsEmptyListWhenHybridSearchFindsNothing() {
        stubHybridResults(List.of());

        List<Document> documents = retriever.retrieve(Query.builder().text("no matches").build());

        assertThat(documents).isEmpty();
    }

    @Test
    void skipsResultsWithoutContentSinceTheyCarryNoRagContext() {
        stubHybridResults(List.of(
                solrDoc("has-content", "usable context", Map.of()),
                solrDoc("no-content", null, Map.of())));

        List<Document> documents = retriever.retrieve(Query.builder().text("q").build());

        assertThat(documents).extracting(Document::getId).containsExactly("has-content");
    }

    @Test
    void passesAPrecomputedVectorFromTheQueryContextToTheRepository() {
        stubHybridResults(List.of());
        float[] vector = {0.1f, 0.2f};

        retriever.retrieve(Query.builder().text("q").context(Map.of(RagContextKeys.VECTOR, vector)).build());

        verify(searchRepository).executeHybridRerankSearch(
                eq(COLLECTION), eq("q"), eq(TOP_K), any(), any(), any(), eq(vector));
    }

    @Test
    void embedsAsBeforeWhenNoVectorIsInTheContext() {
        stubHybridResults(List.of());

        retriever.retrieve(Query.builder().text("q").build());

        verify(searchRepository).executeHybridRerankSearch(
                eq(COLLECTION), eq("q"), eq(TOP_K), any(), any(), any(), isNull());
    }

    @Test
    void ignoresAContextVectorOfTheWrongType() {
        stubHybridResults(List.of());

        retriever.retrieve(Query.builder().text("q").context(Map.of(RagContextKeys.VECTOR, List.of(0.1f))).build());

        verify(searchRepository).executeHybridRerankSearch(
                eq(COLLECTION), eq("q"), eq(TOP_K), any(), any(), any(), isNull());
    }

    @Test
    void ignoresAnEmptyContextVector() {
        stubHybridResults(List.of());

        retriever.retrieve(Query.builder().text("q").context(Map.of(RagContextKeys.VECTOR, new float[0])).build());

        verify(searchRepository).executeHybridRerankSearch(
                eq(COLLECTION), eq("q"), eq(TOP_K), any(), any(), any(), isNull());
    }

    @Test
    void recordsARetrieveObservationTaggedWithTheLeg() {
        stubHybridResults(List.of(solrDoc("doc-1", "content", Map.of())));

        retriever.retrieve(Query.builder().text("q").build());

        assertThat(observationRegistry)
                .hasObservationWithNameEqualTo(RagObservations.RETRIEVE)
                .that()
                .hasLowCardinalityKeyValue(RagObservations.LEG_TAG, "hybrid")
                .hasBeenStarted()
                .hasBeenStopped();
    }

    // ==================== Unfused mode (W3) ====================

    private HybridDocumentRetriever unfusedRetriever() {
        return new HybridDocumentRetriever(searchRepository, COLLECTION, TOP_K, observationRegistry, true);
    }

    private void stubLegs(List<Map<String, Object>> keyword, List<Map<String, Object>> vector) throws Exception {
        when(searchRepository.executeKeywordSearch(any(), any(), anyInt(), any(), any())).thenReturn(keyword);
        when(searchRepository.executeVectorSearch(any(), any(), anyInt(), any(), any(), any())).thenReturn(vector);
    }

    @Test
    void unfusedModeReturnsBothLegsTaggedWithLegAndRank() throws Exception {
        stubLegs(List.of(solrDoc("k1", "a", Map.of("score", 9.0)), solrDoc("both", "b", Map.of("score", 7.0))),
                List.of(solrDoc("both", "b", Map.of("score", 0.9))));

        List<Document> hits = unfusedRetriever().retrieve(Query.builder().text("q").build());

        assertThat(hits).extracting(Document::getId).containsExactly("k1", "both", "both");
        assertThat(hits.get(0).getMetadata())
                .containsEntry(RagContextKeys.LEG, "keyword")
                .containsEntry(RagContextKeys.LEG_RANK, 1)
                .containsEntry("keyword_score", 9.0);
        assertThat(hits.get(2).getMetadata())
                .containsEntry(RagContextKeys.LEG, "vector")
                .containsEntry(RagContextKeys.LEG_RANK, 1)
                .containsEntry("vector_score", 0.9);
    }

    @Test
    void unfusedModeOverFetchesEachLegAndNeverCallsTheFusedSearch() throws Exception {
        stubLegs(List.of(), List.of());
        float[] vector = {0.5f};

        unfusedRetriever().retrieve(Query.builder().text("q").context(Map.of(RagContextKeys.VECTOR, vector)).build());

        verify(searchRepository).executeKeywordSearch(eq(COLLECTION), eq("q"), eq(TOP_K * 2), isNull(),
                eq(HybridDocumentRetriever.PROJECTED_FIELDS));
        verify(searchRepository).executeVectorSearch(eq(COLLECTION), eq("q"), eq(TOP_K * 2), isNull(),
                eq(HybridDocumentRetriever.PROJECTED_FIELDS), eq(vector));
        verify(searchRepository, never()).executeHybridRerankSearch(any(), any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void aFailingLegIsSkippedAndTheOtherLegStillAnswers() throws Exception {
        // e.g. an embedding outage: today's cascade falls back to keyword-only results.
        when(searchRepository.executeKeywordSearch(any(), any(), anyInt(), any(), any()))
                .thenReturn(List.of(solrDoc("k1", "a", Map.of())));
        when(searchRepository.executeVectorSearch(any(), any(), anyInt(), any(), any(), any()))
                .thenThrow(new IllegalStateException("embedding service down"));

        List<Document> hits = unfusedRetriever().retrieve(Query.builder().text("q").build());

        assertThat(hits).extracting(Document::getId).containsExactly("k1");
    }

    @Test
    void bothLegsFailingYieldsNoDocumentsRatherThanAnError() throws Exception {
        when(searchRepository.executeKeywordSearch(any(), any(), anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("solr down"));
        when(searchRepository.executeVectorSearch(any(), any(), anyInt(), any(), any(), any()))
                .thenThrow(new IllegalStateException("solr down"));

        assertThat(unfusedRetriever().retrieve(Query.builder().text("q").build())).isEmpty();
    }

    @Test
    void unfusedModeSkipsRowsWithoutContentWithoutLeavingRankGaps() throws Exception {
        stubLegs(List.of(solrDoc("no-content", null, Map.of()), solrDoc("k2", "b", Map.of())), List.of());

        List<Document> hits = unfusedRetriever().retrieve(Query.builder().text("q").build());

        assertThat(hits).extracting(Document::getId).containsExactly("k2");
        assertThat(hits.getFirst().getMetadata()).containsEntry(RagContextKeys.LEG_RANK, 1);
    }

    @Test
    void unfusedModeRecordsOneRetrieveObservationPerLeg() throws Exception {
        stubLegs(List.of(), List.of());

        unfusedRetriever().retrieve(Query.builder().text("q").build());

        assertThat(observationRegistry)
                .hasNumberOfObservationsWithNameEqualTo(RagObservations.RETRIEVE, 2)
                .hasAnObservationWithAKeyValue(RagObservations.LEG_TAG, "keyword")
                .hasAnObservationWithAKeyValue(RagObservations.LEG_TAG, "vector");
    }

    @Test
    @Timeout(10)
    void anInterruptedCallerFailsFastAndInterruptsTheInFlightLegs() throws Exception {
        CountDownLatch neverReleased = new CountDownLatch(1);
        AtomicBoolean legInterrupted = new AtomicBoolean();
        when(searchRepository.executeKeywordSearch(any(), any(), anyInt(), any(), any())).thenAnswer(invocation -> {
            try {
                neverReleased.await();
                return List.of();
            } catch (InterruptedException e) {
                legInterrupted.set(true);
                throw e;
            }
        });
        lenient().when(searchRepository.executeVectorSearch(any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(List.of());

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> unfusedRetriever().retrieve(Query.builder().text("q").build()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).as("interrupt flag restored").isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(legInterrupted).as("blocked leg was interrupted, not awaited").isTrue();
    }

    @Test
    void exposesItsModeSoTheJoinerCanFollowIt() {
        assertThat(unfusedRetriever().defersFusion()).isTrue();
        assertThat(retriever.defersFusion()).isFalse();
    }

    // ==================== Filters (W1) ====================

    private static Query filtered(List<String> filters) {
        return Query.builder().text("q").context(Map.of(RagContextKeys.FILTERS, filters)).build();
    }

    @Test
    void validatedFiltersAreAppliedToBothLegsAsOneFilterQuery() throws Exception {
        stubLegs(List.of(solrDoc("a", "a", Map.of()), solrDoc("b", "b", Map.of()), solrDoc("c", "c", Map.of())), List.of());

        unfusedRetriever().retrieve(filtered(List.of("metadata_author:\"George R.R. Martin\"", "metadata_price:[* TO 9]")));

        String fq = "metadata_author:\"George R.R. Martin\" AND metadata_price:[* TO 9]";
        verify(searchRepository).executeKeywordSearch(eq(COLLECTION), eq("q"), anyInt(), eq(fq), any());
        verify(searchRepository).executeVectorSearch(eq(COLLECTION), eq("q"), anyInt(), eq(fq), any(), any());
    }

    @Test
    void fewerThanThreeFilteredCandidatesRetriesWithoutFilters() throws Exception {
        when(searchRepository.executeKeywordSearch(any(), any(), anyInt(), any(), any()))
                .thenReturn(List.of(solrDoc("only", "a", Map.of())))
                .thenReturn(List.of(solrDoc("x", "x", Map.of()), solrDoc("y", "y", Map.of()), solrDoc("z", "z", Map.of())));
        when(searchRepository.executeVectorSearch(any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(List.of(solrDoc("only", "a", Map.of())))
                .thenReturn(List.of());

        List<Document> hits = unfusedRetriever().retrieve(filtered(List.of("metadata_year:2011")));

        assertThat(hits).extracting(Document::getId).containsExactly("x", "y", "z");
        verify(searchRepository).executeKeywordSearch(any(), any(), anyInt(), eq("metadata_year:2011"), any());
        verify(searchRepository).executeKeywordSearch(any(), any(), anyInt(), isNull(), any());
    }

    @Test
    void threeOrMoreFilteredCandidatesAreKept() throws Exception {
        stubLegs(List.of(solrDoc("a", "a", Map.of()), solrDoc("b", "b", Map.of())), List.of(solrDoc("c", "c", Map.of())));

        unfusedRetriever().retrieve(filtered(List.of("metadata_year:2011")));

        verify(searchRepository, times(1)).executeKeywordSearch(any(), any(), anyInt(), any(), any());
    }

    @Test
    void fusedModeAlsoAppliesFiltersAndTheFallback() {
        when(searchRepository.executeHybridRerankSearch(any(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(new SearchResponse(List.of(), Map.of(), Map.of(), null))
                .thenReturn(new SearchResponse(List.of(solrDoc("a", "a", Map.of())), Map.of(), Map.of(), null));

        List<Document> hits = retriever.retrieve(filtered(List.of("metadata_year:2011")));

        assertThat(hits).extracting(Document::getId).containsExactly("a");
        verify(searchRepository).executeHybridRerankSearch(any(), any(), anyInt(), eq("metadata_year:2011"), any(), any(), any());
        verify(searchRepository).executeHybridRerankSearch(any(), any(), anyInt(), isNull(), any(), any(), any());
    }

    @Test
    void absentEmptyOrMalformedFiltersMeanNoFilterQuery() {
        assertThat(HybridDocumentRetriever.filterQuery(new Query("q"))).isNull();
        assertThat(HybridDocumentRetriever.filterQuery(filtered(List.of()))).isNull();
        assertThat(HybridDocumentRetriever.filterQuery(
                Query.builder().text("q").context(Map.of(RagContextKeys.FILTERS, "metadata_year:2011")).build())).isNull();
        assertThat(HybridDocumentRetriever.filterQuery(
                Query.builder().text("q").context(Map.of(RagContextKeys.FILTERS, List.of(" ", 7))).build())).isNull();
    }
}
