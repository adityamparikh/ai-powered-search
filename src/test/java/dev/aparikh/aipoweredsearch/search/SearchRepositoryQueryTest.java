package dev.aparikh.aipoweredsearch.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.aparikh.aipoweredsearch.embedding.EmbeddingService;
import dev.aparikh.aipoweredsearch.search.model.FieldInfo;
import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import java.io.IOException;
import java.util.List;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SpellCheckResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.SolrParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link SearchRepository}'s keyword search ({@code search}), the vector fallback's
 * {@code minScore}, and the schema-introspection fallback. Hybrid request shape and the fallback
 * cascade are in {@link SearchRepositoryTest}; schema introspection against real Solr is in {@link
 * SearchRepositorySolrIT}.
 */
@ExtendWith(MockitoExtension.class)
class SearchRepositoryQueryTest {

  private static final String COLLECTION = "books";

  @Mock private SolrClient solrClient;
  @Mock private EmbeddingService embeddingService;
  @Mock private QueryResponse queryResponse;

  private SearchRepository repository;

  @BeforeEach
  void setUp() {
    repository = new SearchRepository(solrClient, embeddingService);
  }

  @Test
  void searchSendsEveryPartOfTheGeneratedQuery() throws Exception {
    stubSearch();

    repository.search(
        COLLECTION,
        new SearchRequest(
            "spring boot",
            List.of("metadata_year:[2020 TO *]", "category:tech"),
            "metadata_year desc",
            "id,content",
            new SearchRequest.Facet(List.of("category", "author"), "metadata_price:[* TO 20]")));

    SolrQuery query = captureSearch();
    assertThat(query.getQuery()).isEqualTo("spring boot");
    assertThat(query.get("defType")).isEqualTo("edismax");
    assertThat(query.get("qf")).isEqualTo("_text_");
    assertThat(query.getFilterQueries())
        .containsExactly("metadata_year:[2020 TO *]", "category:tech");
    assertThat(query.get("sort")).isEqualTo("metadata_year desc");
    assertThat(query.getFields()).isEqualTo("id,content");
    assertThat(query.getFacetFields()).containsExactly("category", "author");
    assertThat(query.getFacetQuery()).containsExactly("metadata_price:[* TO 20]");
    assertThat(query.get("spellcheck.q")).isEqualTo("spring boot");
  }

  @Test
  void aWildcardQueryIsSentToTheStandardParser() throws Exception {
    stubSearch();

    repository.search(COLLECTION, new SearchRequest("*:*", null, null, null, null));

    SolrQuery query = captureSearch();
    assertThat(query.get("defType")).isNull();
    assertThat(query.getFilterQueries()).isNull();
    assertThat(query.getFacetFields()).isNull();
  }

  @Test
  void facetsWithoutAQueryAddOnlyTheFields() throws Exception {
    stubSearch();

    repository.search(
        COLLECTION,
        new SearchRequest("q", null, null, null, new SearchRequest.Facet(List.of("author"), null)));

    SolrQuery query = captureSearch();
    assertThat(query.getFacetFields()).containsExactly("author");
    assertThat(query.getFacetQuery()).isNull();
  }

  @Test
  void searchMapsFacetCountsAndASpellingSuggestion() throws Exception {
    FacetField category = new FacetField("category");
    category.add("tech", 3);
    category.add("science", 1);
    SpellCheckResponse spellCheck = mock(SpellCheckResponse.class);
    when(spellCheck.getCollatedResult()).thenReturn("spring boot");
    when(queryResponse.getFacetFields()).thenReturn(List.of(category));
    when(queryResponse.getSpellCheckResponse()).thenReturn(spellCheck);
    stubSearch(doc("1"));

    SearchResponse response =
        repository.search(COLLECTION, new SearchRequest("sprng boot", null, null, null, null));

    assertThat(response.documents()).extracting(d -> d.get("id")).containsExactly("1");
    assertThat(response.facetCounts().get("category"))
        .containsExactly(
            new SearchResponse.FacetCount("tech", 3), new SearchResponse.FacetCount("science", 1));
    assertThat(response.spellCheckSuggestion())
        .isEqualTo(new SearchResponse.SpellCheckSuggestion("spring boot", "sprng boot"));
  }

  @Test
  void noSpellingSuggestionWhenTheCollationIsTheQueryItself() throws Exception {
    SpellCheckResponse spellCheck = mock(SpellCheckResponse.class);
    when(spellCheck.getCollatedResult()).thenReturn("spring");
    when(queryResponse.getSpellCheckResponse()).thenReturn(spellCheck);
    stubSearch();

    SearchResponse response =
        repository.search(COLLECTION, new SearchRequest("spring", null, null, null, null));

    assertThat(response.spellCheckSuggestion()).isNull();
    assertThat(response.facetCounts()).isEmpty();
  }

  @Test
  void searchWrapsSolrFailures() throws Exception {
    when(solrClient.query(eq(COLLECTION), any(SolrQuery.class)))
        .thenThrow(new SolrServerException("down"));

    assertThatThrownBy(
            () -> repository.search(COLLECTION, new SearchRequest("q", null, null, null, null)))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(SolrServerException.class);
  }

  @Test
  void vectorFallbackAppliesMinScoreButKeepsUnscoredDocuments() throws Exception {
    when(embeddingService.embedAndFormatForSolr("q")).thenReturn("[0.1]");
    when(solrClient.request(any(SolrRequest.class), eq(COLLECTION)))
        .thenThrow(new IOException("hybrid down"));
    SolrDocument close = doc("close");
    close.addField("score", 0.9f);
    SolrDocument far = doc("far");
    far.addField("score", 0.3f);
    SolrDocument unscored = doc("unscored");
    SolrDocumentList vectorHits = new SolrDocumentList();
    vectorHits.addAll(List.of(close, far, unscored));
    when(queryResponse.getResults()).thenReturn(new SolrDocumentList(), vectorHits);
    when(solrClient.query(eq(COLLECTION), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
        .thenReturn(queryResponse);

    SearchResponse response =
        repository.executeHybridRerankSearch(COLLECTION, "q", 10, null, null, 0.5);

    assertThat(response.documents())
        .extracting(d -> d.get("id"))
        .containsExactly("close", "unscored");
  }

  @Test
  void usedFieldsAreEmptyWhenSolrFails() throws Exception {
    when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenThrow(new IOException("down"));

    assertThat(repository.getActuallyUsedFields(COLLECTION)).isEmpty();
  }

  @Test
  void schemaIntrospectionFallsBackToUntypedFieldsWhenTheSchemaApiFails() throws Exception {
    when(solrClient.request(any(SolrRequest.class), eq(COLLECTION)))
        .thenThrow(new SolrServerException("schema API unavailable"));
    SolrDocument sample = doc("1");
    sample.addField("title", "t");
    sample.addField("_version_", 1L);
    stubSearch(sample);

    List<FieldInfo> fields = repository.getFieldsWithSchema(COLLECTION);

    assertThat(fields)
        .containsExactlyInAnyOrder(
            new FieldInfo("id", "unknown", false, true, false, true),
            new FieldInfo("title", "unknown", false, true, false, true));
  }

  private void stubSearch(SolrDocument... docs) throws Exception {
    SolrDocumentList list = new SolrDocumentList();
    list.addAll(List.of(docs));
    when(queryResponse.getResults()).thenReturn(list);
    when(solrClient.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(queryResponse);
  }

  private SolrQuery captureSearch() throws Exception {
    ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
    verify(solrClient).query(eq(COLLECTION), captor.capture());
    return captor.getValue();
  }

  private static SolrDocument doc(String id) {
    SolrDocument doc = new SolrDocument();
    doc.addField("id", id);
    return doc;
  }
}
