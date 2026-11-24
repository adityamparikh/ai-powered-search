package dev.aparikh.aipoweredsearch.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.aparikh.aipoweredsearch.config.SolrTestContainers;
import dev.aparikh.aipoweredsearch.embedding.EmbeddingService;
import dev.aparikh.aipoweredsearch.evaluation.RagEvalFixture;
import dev.aparikh.aipoweredsearch.search.model.FieldInfo;
import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solr.SolrContainer;

/**
 * {@link SearchRepository}'s keyword search and schema introspection against a real Solr built from
 * the project configset. Needs Docker but no API keys: nothing here embeds a query.
 */
@Testcontainers
class SearchRepositorySolrIT {

  private static final String COLLECTION = "search-repository-it";

  @Container static final SolrContainer solr = SolrTestContainers.newSolrContainer();

  private static SolrClient solrClient;
  private static SearchRepository repository;

  @BeforeAll
  static void setUp() throws Exception {
    solrClient =
        new HttpJdkSolrClient.Builder(
                "http://" + solr.getHost() + ":" + solr.getSolrPort() + "/solr")
            .build();
    RagEvalFixture.uploadConfigSet(solr);
    RagEvalFixture.createCollection(solrClient, COLLECTION);

    solrClient.add(
        COLLECTION,
        List.of(
            book("1", "Spring Boot in practice", "alice", "tech", 2021, 30.0, 5),
            book("2", "Spring AI cookbook", "bob", "tech", 2024, 45.0, 3),
            book("3", "A history of gardening", "alice", "garden", 2010, 15.0, 1)));
    solrClient.commit(COLLECTION);

    repository = new SearchRepository(solrClient, mock(EmbeddingService.class));
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (solrClient != null) {
      solrClient.close();
    }
  }

  @Test
  void keywordSearchFiltersSortsAndProjects() {
    SearchResponse response =
        repository.search(
            COLLECTION,
            new SearchRequest("spring", List.of("metadata_year:[2022 TO *]"), null, "id", null));

    assertThat(response.documents()).extracting(d -> d.get("id")).containsExactly("2");
    assertThat(response.documents().getFirst()).containsOnlyKeys("id");
  }

  @Test
  void wildcardSearchHonoursTheSort() {
    SearchResponse response =
        repository.search(
            COLLECTION, new SearchRequest("*:*", null, "metadata_year asc", "id", null));

    assertThat(response.documents()).extracting(d -> d.get("id")).containsExactly("3", "1", "2");
  }

  @Test
  void countsFacets() {
    SearchResponse response =
        repository.search(
            COLLECTION,
            new SearchRequest(
                "*:*", null, null, "id", new SearchRequest.Facet(List.of("category"), null)));

    assertThat(response.facetCounts().get("category"))
        .containsExactlyInAnyOrder(
            new SearchResponse.FacetCount("tech", 2), new SearchResponse.FacetCount("garden", 1));
  }

  @Test
  void reportsTheStoredFieldsDocumentsActuallyUse() {
    assertThat(repository.getActuallyUsedFields(COLLECTION))
        .contains("id", "content", "author", "category", "metadata_year", "pages_i", "isbn_s")
        .doesNotContain("_text_");
  }

  @Test
  void resolvesExplicitAndDynamicFieldTypes() {
    Map<String, FieldInfo> fields =
        repository.getFieldsWithSchema(COLLECTION).stream()
            .collect(Collectors.toMap(FieldInfo::name, Function.identity()));

    // Explicit fields, including the typed metadata the fixture declares.
    assertThat(fields.get("author").type()).isEqualTo("string");
    assertThat(fields.get("metadata_year").type()).isEqualTo("pint");
    assertThat(fields.get("metadata_price").type()).isEqualTo("pdouble");
    // Dynamic fields: suffix patterns (*_i, *_s) and the metadata_* prefix.
    assertThat(fields.get("pages_i").type()).isEqualTo("pint");
    assertThat(fields.get("isbn_s").type()).isEqualTo("string");
    assertThat(fields.get("metadata_genre"))
        .isEqualTo(new FieldInfo("metadata_genre", "text_general", true, true, false, true));
    // Internal fields are never reported.
    assertThat(fields.keySet()).noneMatch(name -> name.startsWith("_"));
  }

  private static SolrInputDocument book(
      String id,
      String content,
      String author,
      String category,
      int year,
      double price,
      int pages) {
    SolrInputDocument doc = new SolrInputDocument();
    doc.addField("id", id);
    doc.addField("content", content);
    doc.addField("author", author);
    doc.addField("category", category);
    doc.addField("metadata_year", year);
    doc.addField("metadata_price", price);
    doc.addField("metadata_genre", "non-fiction");
    doc.addField("pages_i", pages);
    doc.addField("isbn_s", "isbn-" + id);
    return doc;
  }
}
