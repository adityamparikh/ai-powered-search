package dev.aparikh.aipoweredsearch.solr.vectorstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aparikh.aipoweredsearch.config.SolrTestContainers;
import dev.aparikh.aipoweredsearch.evaluation.RagEvalFixture;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solr.SolrContainer;

/**
 * {@link SolrVectorStore} against a real Solr, with no API key: {@link KeywordAxisEmbeddingModel}
 * stands in for OpenAI, so similarity is decided by shared keywords.
 *
 * <p>The collection is built from the project configset with the typed metadata fields the RAG
 * evaluation uses ({@code metadata_author} string, {@code metadata_year} pint, {@code
 * metadata_price} pdouble), so the filter translations are checked against real Solr parsing.
 *
 * <pre>
 * id        text                          author  year  price
 * solr      solr search engine guide      alice   2020  30.0
 * spring    spring framework in action    bob     2022  45.5
 * python    python for data science       alice   2024  25.0
 * cooking   cooking pasta at home         carol   2018  10.0   (embedding supplied, not generated)
 * </pre>
 */
@Testcontainers
class SolrVectorStoreKeylessIT {

  private static final String COLLECTION = "vector-store-keyless-it";

  @Container static final SolrContainer solr = SolrTestContainers.newSolrContainer();

  private static SolrClient solrClient;

  private KeywordAxisEmbeddingModel embeddingModel;
  private SolrVectorStore vectorStore;

  @BeforeAll
  static void createCollection() throws Exception {
    solrClient =
        new HttpJdkSolrClient.Builder(
                "http://" + solr.getHost() + ":" + solr.getSolrPort() + "/solr")
            .build();
    RagEvalFixture.uploadConfigSet(solr);
    RagEvalFixture.createCollection(solrClient, COLLECTION);
  }

  @AfterAll
  static void closeClient() throws Exception {
    if (solrClient != null) {
      solrClient.close();
    }
  }

  @BeforeEach
  void indexCorpus() throws Exception {
    solrClient.deleteByQuery(COLLECTION, "*:*");
    solrClient.commit(COLLECTION);

    embeddingModel = new KeywordAxisEmbeddingModel();
    vectorStore = SolrVectorStore.builder(solrClient, COLLECTION, embeddingModel).build();

    Map<String, Object> cookingMetadata = metadata("carol", 2018, 10.0);
    cookingMetadata.put("embedding", KeywordAxisEmbeddingModel.axis(3));

    vectorStore.add(
        List.of(
            doc("solr", "solr search engine guide", metadata("alice", 2020, 30.0)),
            doc("spring", "spring framework in action", metadata("bob", 2022, 45.5)),
            doc("python", "python for data science", metadata("alice", 2024, 25.0)),
            doc("cooking", "cooking pasta at home", cookingMetadata)));
  }

  @Test
  void generatesEmbeddingsInOneBatchOnlyForDocumentsWithoutOne() {
    // Three generated in one call; "cooking" brought its own.
    assertThat(embeddingModel.embeddedTexts()).isEqualTo(3);
  }

  @Test
  void ranksTheDocumentSharingTheQueryDirectionFirst() {
    List<Document> results = search(SearchRequest.builder().query("solr").topK(4).build());

    assertThat(results).hasSize(4);
    assertThat(results.getFirst().getId()).isEqualTo("solr");
    assertThat(results.getFirst().getText()).isEqualTo("solr search engine guide");
  }

  @Test
  void suppliedEmbeddingIsStoredAsGiven() {
    List<Document> results = search(SearchRequest.builder().query("cooking").topK(1).build());

    assertThat(ids(results)).containsExactly("cooking");
  }

  @Test
  void mapsMetadataAndScoreBack() {
    Document solrDoc = search(SearchRequest.builder().query("solr").topK(1).build()).getFirst();

    assertThat(solrDoc.getMetadata())
        .containsEntry("author", "alice")
        .containsEntry("year", 2020)
        .containsEntry("price", 30.0)
        .containsKey("score");
    // Lucene's cosine score is (1 + cos) / 2: an identical direction scores 1.
    assertThat((Double) solrDoc.getMetadata().get("score")).isEqualTo(1.0);
  }

  @Test
  void similarityThresholdDropsDocumentsBelowIt() {
    // Every other document is orthogonal to "solr": cosine 0, score 0.5.
    List<Document> results =
        search(SearchRequest.builder().query("solr").topK(4).similarityThreshold(0.9).build());

    assertThat(ids(results)).containsExactly("solr");
  }

  @Test
  void equalityFilter() {
    assertThat(filtered("author == 'alice'")).containsExactlyInAnyOrder("solr", "python");
  }

  @Test
  void inequalityFilter() {
    assertThat(filtered("author != 'alice'")).containsExactlyInAnyOrder("spring", "cooking");
  }

  @Test
  void rangeFilters() {
    assertThat(filtered("year > 2020")).containsExactlyInAnyOrder("spring", "python");
    assertThat(filtered("year >= 2022")).containsExactlyInAnyOrder("spring", "python");
    assertThat(filtered("year < 2022")).containsExactlyInAnyOrder("solr", "cooking");
    assertThat(filtered("year <= 2020")).containsExactlyInAnyOrder("solr", "cooking");
    assertThat(filtered("price < 26.0")).containsExactlyInAnyOrder("python", "cooking");
  }

  @Test
  void booleanAndGroupedFilters() {
    assertThat(filtered("author == 'alice' && year > 2020")).containsExactly("python");
    assertThat(filtered("author == 'bob' || author == 'carol'"))
        .containsExactlyInAnyOrder("spring", "cooking");
    assertThat(filtered("(author == 'alice' || author == 'bob') && year >= 2022"))
        .containsExactlyInAnyOrder("spring", "python");
  }

  @Test
  void inFilter() {
    // Regression: IN used to render as metadata_author:([bob, carol]), which Solr rejects as a
    // malformed range. It is now a disjunction: metadata_author:("bob" OR "carol").
    assertThat(filtered("author in ['bob', 'carol']"))
        .containsExactlyInAnyOrder("spring", "cooking");
    assertThat(filtered("year in [2020, 2024]")).containsExactlyInAnyOrder("solr", "python");
  }

  @Test
  void deletedDocumentsAreNoLongerFound() {
    vectorStore.delete(List.of("cooking"));

    // Deletes use commitWithin rather than an explicit commit (unlike add), so they become
    // visible within about a second, not immediately.
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(ids(search(SearchRequest.builder().query("cooking").topK(4).build())))
                    .doesNotContain("cooking")
                    .hasSize(3));
  }

  private List<String> filtered(String expression) {
    return ids(
        search(
            SearchRequest.builder()
                .query("anything")
                .topK(4)
                .filterExpression(expression)
                .build()));
  }

  private List<Document> search(SearchRequest request) {
    List<Document> results = vectorStore.similaritySearch(request);
    assertThat(results).isNotNull();
    return results;
  }

  private static List<String> ids(List<Document> documents) {
    return documents.stream().map(Document::getId).toList();
  }

  private static Document doc(String id, String text, Map<String, Object> metadata) {
    return Document.builder().id(id).text(text).metadata(metadata).build();
  }

  private static Map<String, Object> metadata(String author, int year, double price) {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("author", author);
    metadata.put("year", year);
    metadata.put("price", price);
    return metadata;
  }
}
