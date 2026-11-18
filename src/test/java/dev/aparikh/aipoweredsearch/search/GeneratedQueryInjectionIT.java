package dev.aparikh.aipoweredsearch.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.aparikh.aipoweredsearch.config.SolrTestContainers;
import dev.aparikh.aipoweredsearch.embedding.EmbeddingService;
import dev.aparikh.aipoweredsearch.evaluation.RagEvalFixture;
import dev.aparikh.aipoweredsearch.search.model.QueryGenerationResponse;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import dev.aparikh.aipoweredsearch.solr.vectorstore.VectorStoreFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solr.SolrContainer;

/**
 * Cross-collection join probes against a real single-node SolrCloud (issue #64).
 *
 * <p>{@code books} holds {@code b1} and {@code b2}; {@code hr_salaries} holds one record for {@code
 * b1} with {@code metadata_ssn=123456789}. A probe is an oracle when its result for the prefix
 * {@code 123*} (matches) differs from its result for {@code 999*} (does not): the caller learns
 * something about {@code hr_salaries} without querying it.
 *
 * <p>The first tests send parameters to Solr directly and show which ones are oracles. The rest
 * send the same payloads as model output or request parameters through {@link SearchService}, and
 * show that the answer no longer depends on {@code hr_salaries}.
 *
 * <p>Needs Docker, no API keys: the model and the embedding are stubbed.
 */
@Testcontainers
class GeneratedQueryInjectionIT {

  private static final String BOOKS = "books-injection-it";
  private static final String HR = "hr_salaries";
  private static final String MATCHING = "123*";
  private static final String NOT_MATCHING = "999*";
  private static final int DIMENSIONS = 1536;

  @Container static final SolrContainer solr = SolrTestContainers.newSolrContainer();

  private static SolrClient solrClient;
  private static SearchRepository searchRepository;

  @BeforeAll
  static void setUp() throws Exception {
    solrClient =
        new HttpJdkSolrClient.Builder(
                "http://" + solr.getHost() + ":" + solr.getSolrPort() + "/solr")
            .build();
    RagEvalFixture.uploadConfigSet(solr);
    CollectionAdminRequest.createCollection(BOOKS, RagEvalFixture.CONFIGSET, 1, 1)
        .process(solrClient);
    CollectionAdminRequest.createCollection(HR, RagEvalFixture.CONFIGSET, 1, 1).process(solrClient);

    for (String id : List.of("b1", "b2")) {
      SolrInputDocument book = new SolrInputDocument();
      book.addField("id", id);
      book.addField("content", "spring boot book " + id);
      book.addField("vector", unitVector());
      solrClient.add(BOOKS, book);
    }
    SolrInputDocument salary = new SolrInputDocument();
    salary.addField("id", "b1");
    salary.addField("metadata_ssn", "123456789");
    solrClient.add(HR, salary);
    solrClient.commit(BOOKS);
    solrClient.commit(HR);

    EmbeddingService embeddingService = mock(EmbeddingService.class);
    when(embeddingService.embedAndFormatForSolr(anyString())).thenReturn(unitVector().toString());
    searchRepository = new SearchRepository(solrClient, embeddingService);
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (solrClient != null) {
      solrClient.close();
    }
  }

  private static String join(String ssnPrefix) {
    return "{!join fromIndex=" + HR + " from=id to=id}metadata_ssn:" + ssnPrefix;
  }

  private static String joinFunction(String ssnPrefix) {
    return "query({!join fromIndex=" + HR + " from=id to=id v='metadata_ssn:" + ssnPrefix + "'})";
  }

  // ---------------------------------------------------------------- unguarded: straight to Solr

  static Stream<Arguments> oracles() {
    return Stream.of(
        Arguments.of("fq join", (Function<String, Map<String, String>>) p -> fq(join(p))),
        Arguments.of(
            "fq join inline",
            (Function<String, Map<String, String>>) p -> fq("id:* AND " + join(p))),
        Arguments.of(
            "fq join escaped inside _query_",
            (Function<String, Map<String, String>>)
                p ->
                    fq(
                        "_query_:\"\\{!join fromIndex="
                            + HR
                            + " from=id to=id\\}metadata_ssn:"
                            + p
                            + "\"")),
        Arguments.of(
            "sort dereferencing q",
            (Function<String, Map<String, String>>)
                p ->
                    Map.of(
                        "q", "spring " + join(p),
                        "defType", "edismax",
                        "qf", "_text_",
                        "sort", "query($q,0) desc, id desc")),
        Arguments.of(
            "fl pseudo-field",
            (Function<String, Map<String, String>>)
                p -> Map.of("q", "*:*", "fl", "id,leak:exists(" + joinFunction(p) + ")")),
        Arguments.of(
            "facet.query",
            (Function<String, Map<String, String>>)
                p -> Map.of("q", "*:*", "rows", "0", "facet", "true", "facet.query", join(p))));
  }

  private static Map<String, String> fq(String filter) {
    return Map.of("q", "*:*", "fq", filter);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("oracles")
  void solrAnswersDependOnTheOtherCollectionWhenThePayloadReachesIt(
      String name, Function<String, Map<String, String>> payload) throws Exception {
    assertThat(rawAnswer(payload.apply(MATCHING)))
        .isNotEqualTo(rawAnswer(payload.apply(NOT_MATCHING)));
  }

  @Test
  void edismaxIgnoresLocalParamsAndQueryInQ() {
    for (String q :
        List.of(
            "spring " + join(MATCHING),
            join(MATCHING),
            "spring AND _query_:\"" + join(MATCHING) + "\"")) {
      String probe = q.replace(MATCHING, NOT_MATCHING);
      assertThat(keywordIds(q)).as(q).isEqualTo(keywordIds(probe));
    }
  }

  // ------------------------------------------------------------- guarded: through SearchService

  @Test
  void keywordSearchAnswersTheSameWhetherOrNotTheOtherCollectionMatches() {
    Function<String, QueryGenerationResponse> generated =
        p ->
            new QueryGenerationResponse(
                "spring " + join(p),
                List.of(
                    join(p),
                    "_query_:\"\\{!join fromIndex="
                        + HR
                        + " from=id to=id\\}metadata_ssn:"
                        + p
                        + "\""),
                "query($q,0) desc, id desc",
                "id,leak:exists(" + joinFunction(p) + ")",
                null,
                join(p));

    SearchResponse matching = serviceGenerating(generated.apply(MATCHING)).search(BOOKS, "books");
    SearchResponse notMatching =
        serviceGenerating(generated.apply(NOT_MATCHING)).search(BOOKS, "books");

    assertThat(matching.documents()).isEqualTo(notMatching.documents());
    assertThat(ids(matching)).containsExactlyInAnyOrder("b1", "b2");
    assertThat(matching.documents())
        .allSatisfy(doc -> assertThat(doc).doesNotContainKeys("vector", "leak"));
  }

  @Test
  void hybridSearchAnswersTheSameWhetherOrNotTheOtherCollectionMatches() {
    Function<String, SearchResponse> probe =
        p ->
            serviceGenerating(
                    new QueryGenerationResponse("spring", List.of(join(p)), null, null, null, null))
                .hybridSearch(
                    BOOKS, "books", 10, null, "id,vector,leak:exists(" + joinFunction(p) + ")");

    SearchResponse matching = probe.apply(MATCHING);

    assertThat(matching.documents()).isEqualTo(probe.apply(NOT_MATCHING).documents());
    assertThat(ids(matching)).containsExactlyInAnyOrder("b1", "b2");
    assertThat(matching.documents()).allSatisfy(doc -> assertThat(doc).containsOnlyKeys("id"));
  }

  @Test
  void keywordSearchNeverReturnsTheVectorField() {
    SearchResponse response =
        serviceGenerating(new QueryGenerationResponse("spring", null, null, "*", null, null))
            .search(BOOKS, "books");

    assertThat(response.documents())
        .isNotEmpty()
        .allSatisfy(
            doc -> assertThat(doc).containsKeys("id", "content").doesNotContainKey("vector"));
  }

  // ----------------------------------------------------------------------------------- helpers

  private static String rawAnswer(Map<String, String> params) throws Exception {
    ModifiableSolrParams solrParams = new ModifiableSolrParams();
    solrParams.set("fl", "id");
    params.forEach(solrParams::set);
    QueryResponse response = solrClient.query(BOOKS, solrParams, SolrRequest.METHOD.POST);
    return response.getResults() + " " + response.getFacetQuery();
  }

  private static List<Object> keywordIds(String q) {
    return ids(
        searchRepository.search(
            BOOKS,
            new dev.aparikh.aipoweredsearch.search.model.SearchRequest(q, null, null, "id", null)));
  }

  private static List<Object> ids(SearchResponse response) {
    return response.documents().stream().map(doc -> doc.get("id")).toList();
  }

  private static SearchService serviceGenerating(QueryGenerationResponse generated) {
    ChatClient chatClient = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec requestSpec =
        mock(ChatClient.ChatClientRequestSpec.class, Mockito.RETURNS_SELF);
    ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);
    when(callResponseSpec.entity(QueryGenerationResponse.class)).thenReturn(generated);
    return new SearchService(
        new ClassPathResource("prompts/system-message.st"),
        searchRepository,
        chatClient,
        mock(ChatClient.class),
        mock(VectorStoreFactory.class));
  }

  private static List<Float> unitVector() {
    List<Float> vector = new ArrayList<>(DIMENSIONS);
    for (int i = 0; i < DIMENSIONS; i++) {
      vector.add(i == 0 ? 1f : 0f);
    }
    return vector;
  }
}
