package dev.aparikh.aipoweredsearch.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.aparikh.aipoweredsearch.search.model.QueryGenerationResponse;
import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import dev.aparikh.aipoweredsearch.solr.vectorstore.VectorStoreFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;

/**
 * Malicious model output and request parameters never reach the repository, and so never reach Solr
 * (issue #64). {@link SolrParamGuardTest} covers the screening rules themselves.
 */
@ExtendWith(MockitoExtension.class)
class SearchServiceParamGuardTest {

  private static final String COLLECTION = "books";
  private static final String JOIN =
      "{!join fromIndex=hr_salaries from=id to=id}salary:[200000 TO *]";
  private static final String FRANGE = "{!frange l=0}sum(metadata_price,metadata_year)";
  private static final SearchResponse EMPTY = new SearchResponse(List.of(), Map.of());

  @Mock private SearchRepository searchRepository;
  @Mock private ChatClient chatClient;
  @Mock private ChatClient ragChatClient;
  @Mock private VectorStoreFactory vectorStoreFactory;

  private ChatClient.CallResponseSpec callResponseSpec;
  private SearchService searchService;

  @BeforeEach
  void setUp() {
    callResponseSpec = mock(ChatClient.CallResponseSpec.class);
    ChatClient.ChatClientRequestSpec requestSpec =
        mock(ChatClient.ChatClientRequestSpec.class, Mockito.RETURNS_SELF);
    lenient().when(chatClient.prompt()).thenReturn(requestSpec);
    lenient().when(requestSpec.call()).thenReturn(callResponseSpec);
    lenient().when(searchRepository.getFieldsWithSchema(COLLECTION)).thenReturn(List.of());

    searchService =
        new SearchService(
            new ClassPathResource("prompts/system-message.st"),
            searchRepository,
            chatClient,
            ragChatClient,
            vectorStoreFactory);
  }

  private void modelGenerates(QueryGenerationResponse response) {
    when(callResponseSpec.entity(QueryGenerationResponse.class)).thenReturn(response);
  }

  @Test
  void keywordSearchSendsNoJoinFrangeOrStarFieldList() {
    modelGenerates(
        new QueryGenerationResponse(
            "spring", List.of(JOIN, FRANGE, "category:tech"), null, "*", null, null));
    when(searchRepository.search(eq(COLLECTION), any())).thenReturn(EMPTY);

    searchService.search(COLLECTION, "spring books");

    SearchRequest sent = capturedKeywordRequest();
    assertThat(sent.filterQueries()).containsExactly("category:tech");
    assertThat(sent.fieldList()).isNull();
  }

  @Test
  void keywordSearchDropsVectorFromTheGeneratedFieldList() {
    modelGenerates(new QueryGenerationResponse("spring", null, null, "id,vector", null, null));
    when(searchRepository.search(eq(COLLECTION), any())).thenReturn(EMPTY);

    searchService.search(COLLECTION, "spring books");

    assertThat(capturedKeywordRequest().fieldList()).isEqualTo("id");
  }

  @Test
  void keywordSearchDropsSortAndFacetQueryThatRunAJoin() {
    modelGenerates(
        new QueryGenerationResponse(
            "spring",
            null,
            "query({!join fromIndex=hr_salaries from=id to=id v='ssn:1*'},0) desc",
            "vector",
            List.of("category"),
            JOIN));
    when(searchRepository.search(eq(COLLECTION), any())).thenReturn(EMPTY);

    searchService.search(COLLECTION, "spring books");

    SearchRequest sent = capturedKeywordRequest();
    assertThat(sent.sort()).isNull();
    assertThat(sent.fieldList()).isNull();
    assertThat(sent.facet()).isNotNull();
    assertThat(sent.facet().fields()).containsExactly("category");
    assertThat(sent.facet().query()).isNull();
  }

  @Test
  void hybridSearchSendsNoJoinOrFrangeFilter() {
    modelGenerates(
        new QueryGenerationResponse(
            "spring", List.of(JOIN, "metadata_year:[2020 TO *]", FRANGE), null, null, null, null));
    when(searchRepository.executeHybridRerankSearch(
            anyString(), anyString(), anyInt(), any(), any(), any()))
        .thenReturn(EMPTY);

    searchService.hybridSearch(COLLECTION, "spring books", null, null, null);

    verify(searchRepository)
        .executeHybridRerankSearch(
            COLLECTION, "spring", 100, "metadata_year:[2020 TO *]", null, null);
  }

  @Test
  void hybridSearchSendsNoFilterWhenEveryGeneratedClauseIsUnsafe() {
    modelGenerates(
        new QueryGenerationResponse("spring", List.of(JOIN, FRANGE), null, null, null, null));
    when(searchRepository.executeHybridRerankSearch(
            anyString(), anyString(), anyInt(), any(), any(), any()))
        .thenReturn(EMPTY);

    searchService.hybridSearch(COLLECTION, "spring books", null, null, null);

    verify(searchRepository)
        .executeHybridRerankSearch(eq(COLLECTION), eq("spring"), eq(100), isNull(), any(), any());
  }

  @Test
  void hybridSearchScreensTheUserSuppliedFieldsParameter() {
    modelGenerates(new QueryGenerationResponse("spring", null, null, null, null, null));
    when(searchRepository.executeHybridRerankSearch(
            anyString(), anyString(), anyInt(), any(), any(), any()))
        .thenReturn(EMPTY);

    searchService.hybridSearch(
        COLLECTION,
        "spring books",
        null,
        null,
        "id,*,vector,leak:exists(query({!join fromIndex=hr_salaries from=id to=id v='ssn:1*'}))");

    verify(searchRepository).executeHybridRerankSearch(COLLECTION, "spring", 100, null, "id", null);
  }

  @Test
  void hybridSearchUsesTheDefaultFieldListWhenOnlyStarAndVectorAreAsked() {
    modelGenerates(new QueryGenerationResponse("spring", null, null, null, null, null));
    when(searchRepository.executeHybridRerankSearch(
            anyString(), anyString(), anyInt(), any(), any(), any()))
        .thenReturn(EMPTY);

    searchService.hybridSearch(COLLECTION, "spring books", null, null, "*,vector");

    verify(searchRepository).executeHybridRerankSearch(COLLECTION, "spring", 100, null, null, null);
  }

  @Test
  void semanticSearchTreatsAStarFieldListAsTheDefault() {
    VectorStore vectorStore = mock(VectorStore.class);
    when(vectorStoreFactory.forCollection(COLLECTION)).thenReturn(vectorStore);
    when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
        .thenReturn(
            List.of(new Document("1", "Spring in Action", Map.of("metadata_author", "Walls"))));

    SearchResponse response =
        searchService.semanticSearch(COLLECTION, "spring books", null, null, "*,vector");

    assertThat(response.documents().getFirst())
        .containsEntry("content", "Spring in Action")
        .containsEntry("author", "Walls");
  }

  private SearchRequest capturedKeywordRequest() {
    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(searchRepository).search(eq(COLLECTION), captor.capture());
    return captor.getValue();
  }
}
