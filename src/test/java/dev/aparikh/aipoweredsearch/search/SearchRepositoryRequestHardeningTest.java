package dev.aparikh.aipoweredsearch.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.aparikh.aipoweredsearch.embedding.EmbeddingService;
import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import java.io.ByteArrayOutputStream;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.request.json.JsonQueryRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.SolrParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Every request {@link SearchRepository} builds turns off macro expansion, and every edismax query
 * pins {@code uf} so embedded {@code _query_} stays off (issue #64).
 */
@ExtendWith(MockitoExtension.class)
class SearchRepositoryRequestHardeningTest {

  private static final String COLLECTION = "books";
  private static final String QUERY = "spring boot";
  private static final String VECTOR = "[0.1,0.2,0.3]";

  @Mock private SolrClient solrClient;
  @Mock private EmbeddingService embeddingService;
  @Mock private QueryResponse queryResponse;

  private SearchRepository searchRepository;

  @BeforeEach
  void setUp() {
    searchRepository = new SearchRepository(solrClient, embeddingService);
  }

  @Test
  void keywordSearchDisablesMacrosAndPinsUserFields() throws Exception {
    when(queryResponse.getResults()).thenReturn(new SolrDocumentList());
    when(solrClient.query(eq(COLLECTION), any(SolrParams.class))).thenReturn(queryResponse);

    searchRepository.search(COLLECTION, new SearchRequest(QUERY, null, null, null, null));

    ArgumentCaptor<SolrParams> params = ArgumentCaptor.forClass(SolrParams.class);
    verify(solrClient).query(eq(COLLECTION), params.capture());
    assertThat(params.getValue().get(SearchRepository.EXPAND_MACROS)).isEqualTo("false");
    assertThat(params.getValue().get(SearchRepository.UF)).isEqualTo("* -_query_");
  }

  @Test
  void matchAllKeywordSearchStillDisablesMacros() throws Exception {
    when(queryResponse.getResults()).thenReturn(new SolrDocumentList());
    when(solrClient.query(eq(COLLECTION), any(SolrParams.class))).thenReturn(queryResponse);

    searchRepository.search(COLLECTION, new SearchRequest("*:*", null, null, null, null));

    ArgumentCaptor<SolrParams> params = ArgumentCaptor.forClass(SolrParams.class);
    verify(solrClient).query(eq(COLLECTION), params.capture());
    assertThat(params.getValue().get(SearchRepository.EXPAND_MACROS)).isEqualTo("false");
  }

  @Test
  void keywordFallbackDisablesMacrosAndPinsUserFields() throws Exception {
    SolrParams params =
        capturePlainQuery(
            () -> searchRepository.executeKeywordSearch(COLLECTION, QUERY, 10, null, null));

    assertThat(params.get(SearchRepository.EXPAND_MACROS)).isEqualTo("false");
    assertThat(params.get(SearchRepository.UF)).isEqualTo("* -_query_");
  }

  @Test
  void vectorFallbackDisablesMacros() throws Exception {
    when(embeddingService.embedAndFormatForSolr(QUERY)).thenReturn(VECTOR);

    SolrParams params =
        capturePlainQuery(
            () -> searchRepository.executeVectorSearch(COLLECTION, QUERY, 10, null, null));

    assertThat(params.get(SearchRepository.EXPAND_MACROS)).isEqualTo("false");
  }

  @Test
  void hybridRequestDisablesMacrosAndPinsUserFieldsOnTheKeywordLeg() throws Exception {
    JsonQueryRequest request =
        searchRepository.buildHybridRequest(QUERY, VECTOR, 10, "category:tech", "id", null);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    request.getContentWriter("application/json").write(out);
    JsonNode body = new ObjectMapper().readTree(out.toByteArray());

    assertThat(body.path("params").path(SearchRepository.EXPAND_MACROS).asBoolean(true)).isFalse();
    assertThat(
            body.path("queries")
                .path(SearchRepository.KEYWORD_LEG)
                .path("edismax")
                .path(SearchRepository.UF)
                .asString())
        .isEqualTo("* -_query_");
  }

  @FunctionalInterface
  private interface SolrCall {
    void run() throws Exception;
  }

  private SolrParams capturePlainQuery(SolrCall call) throws Exception {
    when(queryResponse.getResults()).thenReturn(new SolrDocumentList());
    when(solrClient.query(eq(COLLECTION), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
        .thenReturn(queryResponse);

    call.run();

    ArgumentCaptor<SolrParams> params = ArgumentCaptor.forClass(SolrParams.class);
    verify(solrClient).query(eq(COLLECTION), params.capture(), eq(SolrRequest.METHOD.POST));
    return params.getValue();
  }
}
