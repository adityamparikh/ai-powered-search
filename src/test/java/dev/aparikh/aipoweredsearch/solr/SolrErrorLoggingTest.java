package dev.aparikh.aipoweredsearch.solr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.aparikh.aipoweredsearch.embedding.EmbeddingService;
import dev.aparikh.aipoweredsearch.indexing.IndexService;
import dev.aparikh.aipoweredsearch.indexing.model.BatchIndexRequest;
import dev.aparikh.aipoweredsearch.indexing.model.IndexRequest;
import dev.aparikh.aipoweredsearch.search.SearchRepository;
import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import dev.aparikh.aipoweredsearch.solr.vectorstore.VectorStoreFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.RemoteSolrException;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.common.params.SolrParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * Solr's error messages quote the query, the filters and document field values. At INFO and above
 * the application logs only the exception type and Solr's error code; the full exception is at
 * DEBUG.
 *
 * <p>The messages below have the shape Solr 10 returns for a filter that does not parse and for a
 * field value that does not index.
 */
class SolrErrorLoggingTest {

  private static final String SECRET = "JaneDoeConfidentialNote";
  private static final String COLLECTION = "books";

  private final SolrClient solrClient = mock(SolrClient.class);
  private final EmbeddingService embeddingService = mock(EmbeddingService.class);
  private final List<Logger> loggers = new ArrayList<>();
  private final List<Level> previousLevels = new ArrayList<>();
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void captureLogs() {
    appender.start();
    for (Class<?> type : List.of(SearchRepository.class, IndexService.class)) {
      Logger logger = (Logger) LoggerFactory.getLogger(type);
      loggers.add(logger);
      previousLevels.add(logger.getLevel());
      logger.setLevel(Level.DEBUG);
      logger.addAppender(appender);
    }
  }

  @AfterEach
  void restoreLoggers() {
    for (int i = 0; i < loggers.size(); i++) {
      loggers.get(i).detachAppender(appender);
      loggers.get(i).setLevel(previousLevels.get(i));
    }
  }

  @Test
  void aFailedKeywordSearchLogsAndThrowsNoQueryText() throws Exception {
    when(solrClient.query(eq(COLLECTION), any(SolrParams.class))).thenThrow(badFilter());

    Throwable thrown =
        catchThrowable(
            () ->
                new SearchRepository(solrClient, embeddingService)
                    .search(
                        COLLECTION,
                        new SearchRequest(
                            "books",
                            List.of("author:\"" + SECRET + "\" AND ("),
                            null,
                            null,
                            null)));

    // The servlet container logs an unhandled exception's cause chain at ERROR.
    assertThat(thrown).isInstanceOf(IllegalStateException.class).hasNoCause();
    assertThat(thrown.getMessage())
        .doesNotContain(SECRET)
        .contains("RemoteSolrException, Solr code 400");
    assertNothingAboveDebugQuotes();
    assertTheFullErrorIsAtDebug();
  }

  @Test
  void aBadHybridFilterLogsOneLinePerStrategyWithoutTheFilter() throws Exception {
    when(embeddingService.embedAndFormatForSolr(anyString())).thenReturn("[0.1,0.2]");
    when(solrClient.request(any(SolrRequest.class), eq(COLLECTION))).thenThrow(badFilter());
    when(solrClient.query(eq(COLLECTION), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
        .thenThrow(badFilter());

    new SearchRepository(solrClient, embeddingService)
        .executeHybridRerankSearch(
            COLLECTION, "books", 10, "author:\"" + SECRET + "\" AND (", null, null);

    // Hybrid, keyword fallback, vector fallback, and "no results": no stack traces.
    assertThat(appender.list)
        .filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
        .hasSize(4)
        .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    assertNothingAboveDebugQuotes();
    assertTheFullErrorIsAtDebug();
  }

  @Test
  void aFailedIndexLogsTheDocumentIdButNoFieldValues() {
    VectorStoreFactory vectorStoreFactory = mock(VectorStoreFactory.class);
    VectorStore vectorStore = mock(VectorStore.class);
    when(vectorStoreFactory.forCollection(anyString())).thenReturn(vectorStore);
    doThrow(new RuntimeException("Failed to add documents to Solr", badFieldValue()))
        .when(vectorStore)
        .add(anyList());
    IndexService indexService = new IndexService(vectorStoreFactory);

    indexService.indexDocument(COLLECTION, new IndexRequest("d1", "A passage", Map.of()));
    indexService.indexDocuments(
        COLLECTION,
        new BatchIndexRequest(List.of(new IndexRequest("d2", "Another passage", Map.of()))));

    assertThat(appender.list)
        .filteredOn(event -> event.getLevel() == Level.ERROR)
        .extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly(
            "Failed to index document 'd1' in collection 'books' "
                + "(RuntimeException caused by RemoteSolrException, Solr code 400)",
            "Failed to batch index documents in collection 'books' "
                + "(RuntimeException caused by RemoteSolrException, Solr code 400)");
    assertNothingAboveDebugQuotes();
    assertTheFullErrorIsAtDebug();
  }

  private void assertNothingAboveDebugQuotes() {
    assertThat(appender.list)
        .filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
        .isNotEmpty()
        .allSatisfy(
            event -> {
              assertThat(event.getFormattedMessage()).doesNotContain(SECRET);
              assertThat(event.getThrowableProxy()).isNull();
            });
  }

  /** The detail is not lost: the local profile's DEBUG level shows the whole exception. */
  private void assertTheFullErrorIsAtDebug() {
    assertThat(appender.list)
        .filteredOn(event -> event.getLevel() == Level.DEBUG && event.getThrowableProxy() != null)
        .isNotEmpty();
  }

  private static RemoteSolrException badFilter() {
    return new RemoteSolrException(
        "http://solr:8983/solr/books",
        400,
        "org.apache.solr.search.SyntaxError: Cannot parse 'author:\""
            + SECRET
            + "\" AND (': "
            + "Encountered \"<EOF>\"",
        null);
  }

  private static RemoteSolrException badFieldValue() {
    return new RemoteSolrException(
        "http://solr:8983/solr/books",
        400,
        "ERROR: [doc=d1] Error adding field 'metadata_price'='" + SECRET + "'",
        null);
  }
}
