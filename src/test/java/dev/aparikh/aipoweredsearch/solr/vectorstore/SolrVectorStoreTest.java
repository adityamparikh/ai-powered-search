package dev.aparikh.aipoweredsearch.solr.vectorstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;

/**
 * Unit tests for the paths of {@link SolrVectorStore} that a real Solr does not easily produce:
 * transport failures, a misbehaving embedding model, and Solr documents with unusual shapes. The
 * happy path against real Solr is {@link SolrVectorStoreKeylessIT}.
 */
@ExtendWith(MockitoExtension.class)
class SolrVectorStoreTest {

  private static final String COLLECTION = "unit";

  @Mock private SolrClient solrClient;
  @Mock private QueryResponse queryResponse;

  private SolrVectorStore store;

  @BeforeEach
  void setUp() {
    store =
        SolrVectorStore.builder(solrClient, COLLECTION, new KeywordAxisEmbeddingModel()).build();
  }

  @Test
  void addWrapsSolrFailures() throws Exception {
    when(solrClient.add(eq(COLLECTION), anyList(), anyInt())).thenThrow(new IOException("down"));

    assertThatThrownBy(() -> store.doAdd(List.of(new Document("solr"))))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Failed to add documents to Solr")
        .hasCauseInstanceOf(IOException.class);
  }

  @Test
  void addRejectsAnEmbeddingModelThatReturnsTheWrongNumberOfVectors() {
    EmbeddingModel shortChanging = new FixedResponseEmbeddingModel(List.of());
    SolrVectorStore shortStore =
        SolrVectorStore.builder(solrClient, COLLECTION, shortChanging).build();

    assertThatThrownBy(() -> shortStore.doAdd(List.of(new Document("a"), new Document("b"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Expected 2 embeddings but got 0");
  }

  @Test
  void deleteWrapsSolrFailures() throws Exception {
    when(solrClient.deleteById(eq(COLLECTION), anyList(), anyInt()))
        .thenThrow(new SolrServerException("down"));

    assertThatThrownBy(() -> store.doDelete(List.of("1")))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Failed to delete documents from Solr");
  }

  @Test
  void searchRejectsAnEmptyQuery() {
    assertThatThrownBy(() -> store.doSimilaritySearch(SearchRequest.builder().query("").build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void searchFailsWhenTheQueryCannotBeEmbedded() {
    SolrVectorStore noVectors =
        SolrVectorStore.builder(solrClient, COLLECTION, new FixedResponseEmbeddingModel(List.of()))
            .build();

    assertThatThrownBy(
            () -> noVectors.doSimilaritySearch(SearchRequest.builder().query("q").build()))
        .hasMessage("Failed to generate embedding for query");
  }

  @Test
  void searchWrapsSolrFailures() throws Exception {
    when(solrClient.query(eq(COLLECTION), any(SolrQuery.class), eq(SolrRequest.METHOD.POST)))
        .thenThrow(new IOException("down"));

    assertThatThrownBy(() -> store.doSimilaritySearch(SearchRequest.builder().query("q").build()))
        .hasMessage("Failed to perform similarity search in Solr");
  }

  @Test
  void searchUsesPostAndTheConfiguredFieldNames() throws Exception {
    SolrVectorStore custom =
        SolrVectorStore.builder(solrClient, COLLECTION, new KeywordAxisEmbeddingModel())
            .options(
                SolrVectorStoreOptions.builder()
                    .idFieldName("doc_id")
                    .contentFieldName("body")
                    .vectorFieldName("emb")
                    .metadataPrefix("m_")
                    .vectorDimension(8)
                    .build())
            .build();
    stubResults();

    custom.doSimilaritySearch(SearchRequest.builder().query("solr").topK(3).build());

    SolrQuery query = captureQuery();
    assertThat(query.getQuery()).startsWith("{!knn f=emb topK=3}");
    assertThat(query.getRows()).isEqualTo(3);
    assertThat(query.getFields()).isEqualTo("doc_id,body,score,m_*");
  }

  @Test
  void unsupportedFilterOperatorsAreDroppedRatherThanSentToSolr() throws Exception {
    stubResults();
    Filter.Expression not =
        new Filter.Expression(
            Filter.ExpressionType.NOT,
            new Filter.Expression(
                Filter.ExpressionType.EQ, new Filter.Key("author"), new Filter.Value("x")));

    store.doSimilaritySearch(SearchRequest.builder().query("q").filterExpression(not).build());

    assertThat(captureQuery().getFilterQueries()).isNull();
  }

  @Test
  void prefixedKeysAreNotPrefixedTwice() throws Exception {
    stubResults();
    Filter.Expression eq =
        new Filter.Expression(
            Filter.ExpressionType.EQ, new Filter.Key("metadata_author"), new Filter.Value("x"));

    store.doSimilaritySearch(SearchRequest.builder().query("q").filterExpression(eq).build());

    assertThat(captureQuery().getFilterQueries()).containsExactly("metadata_author:\"x\"");
  }

  @Test
  void mapsMultiValuedSolrFieldsToTheirFirstValue() throws Exception {
    SolrDocument doc = new SolrDocument();
    doc.setField("id", List.of("multi"));
    doc.setField("content", List.of("first", "second"));
    doc.setField("score", 0.8f);
    doc.setField("metadata_year", List.of(2021L));
    doc.setField("metadata_tags", List.of("a", "b"));
    doc.setField("metadata_count", 7L);
    doc.setField("metadata_empty", List.of());
    stubResults(doc);

    Document result =
        store.doSimilaritySearch(SearchRequest.builder().query("q").build()).getFirst();

    assertThat(result.getId()).isEqualTo("multi");
    assertThat(result.getText()).isEqualTo("first");
    assertThat(result.getMetadata())
        .containsEntry("year", 2021)
        .containsEntry("tags", "a")
        .containsEntry("count", 7L)
        .doesNotContainKey("empty");
  }

  @Test
  void convertsAScalarLongYearToAnInteger() throws Exception {
    SolrDocument doc = new SolrDocument();
    doc.setField("id", "scalar");
    doc.setField("content", "text");
    doc.setField("score", 0.7f);
    doc.setField("metadata_year", 1999L);
    stubResults(doc);

    Document result =
        store.doSimilaritySearch(SearchRequest.builder().query("q").build()).getFirst();

    assertThat(result.getMetadata()).containsEntry("year", 1999).containsKey("score");
  }

  @Test
  void skipsDocumentsWithoutAnIdOrContentAndDocumentsBelowTheThreshold() throws Exception {
    SolrDocument noId = new SolrDocument();
    noId.setField("content", "orphan");
    SolrDocument emptyIdList = new SolrDocument();
    emptyIdList.setField("id", List.of());
    SolrDocument noContent = new SolrDocument();
    noContent.setField("id", "no-content");
    noContent.setField("score", 0.9f);
    SolrDocument low = new SolrDocument();
    low.setField("id", "low");
    low.setField("content", "low");
    low.setField("score", 0.2f);
    SolrDocument unscored = new SolrDocument();
    unscored.setField("id", "unscored");
    unscored.setField("content", "unscored");
    SolrDocument kept = new SolrDocument();
    kept.setField("id", "kept");
    kept.setField("content", "kept");
    kept.setField("score", 0.9f);
    stubResults(noId, emptyIdList, noContent, low, unscored, kept);

    List<Document> results =
        store.doSimilaritySearch(
            SearchRequest.builder().query("q").similarityThreshold(0.5).build());

    // Regression: a hit with no stored content used to fail the whole search, because Spring AI's
    // Document requires text. It is now skipped. Below the threshold is dropped, and so is a hit
    // with no score, which cannot be judged against it.
    assertThat(results).extracting(Document::getId).containsExactly("kept");
  }

  @Test
  void exposesItsClientAndObservationContext() {
    assertThat(store.getNativeClient()).containsSame(solrClient);
    assertThat(store.createObservationContextBuilder("query").build().getCollectionName())
        .isEqualTo(COLLECTION);
  }

  @Test
  void buildsWithSchemaInitialisationRequested() {
    SolrVectorStore initialising =
        SolrVectorStore.builder(solrClient, COLLECTION, new KeywordAxisEmbeddingModel())
            .initializeSchema(true)
            .build();

    assertThat(initialising).isNotNull();
  }

  private void stubResults(SolrDocument... documents) throws Exception {
    SolrDocumentList list = new SolrDocumentList();
    list.addAll(List.of(documents));
    when(queryResponse.getResults()).thenReturn(list);
    when(solrClient.query(eq(COLLECTION), any(SolrQuery.class), eq(SolrRequest.METHOD.POST)))
        .thenReturn(queryResponse);
  }

  private SolrQuery captureQuery() throws Exception {
    ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
    verify(solrClient).query(eq(COLLECTION), captor.capture(), eq(SolrRequest.METHOD.POST));
    return captor.getValue();
  }

  /** Returns the same embeddings whatever it is asked, to simulate a misbehaving model. */
  private record FixedResponseEmbeddingModel(List<Embedding> embeddings) implements EmbeddingModel {

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
      return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
      return new float[0];
    }

    @Override
    public float[] embed(String text) {
      return new float[0];
    }

    @Override
    public int dimensions() {
      return 0;
    }
  }
}
