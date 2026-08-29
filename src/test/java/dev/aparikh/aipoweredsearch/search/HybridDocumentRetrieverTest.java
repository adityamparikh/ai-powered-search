package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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

    private HybridDocumentRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new HybridDocumentRetriever(searchRepository, COLLECTION, TOP_K);
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
                any(), any(), anyInt(), any(), any(), any()))
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
                eq(COLLECTION), eq("q"), eq(TOP_K), any(), fieldsCaptor.capture(), any());

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
                eq(COLLECTION), eq("machine learning frameworks"), eq(TOP_K), any(), any(), any());
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
}
