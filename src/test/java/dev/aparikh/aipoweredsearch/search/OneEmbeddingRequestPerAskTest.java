package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.embedding.EmbeddingService;
import dev.aparikh.aipoweredsearch.search.rag.EmbeddingBatcher;
import dev.aparikh.aipoweredsearch.search.rag.QueryPlanningExpander;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.SolrParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W2 acceptance at the component boundary: one planned {@code /ask} turn, three queries, makes
 * exactly one embedding request, and BM25 never sees the HyDE passage.
 *
 * <p>Runs the real planner output through the real retriever and {@link SearchRepository}. Only
 * the chat model, the embedding model and the {@link SolrClient} are test doubles, so the
 * assertions sit where the cost is: embedding requests at the model, and {@code q} at Solr.</p>
 */
class OneEmbeddingRequestPerAskTest {

    private static final String HYDE = "A sweeping saga in which rival noble houses scheme for a contested throne.";
    private static final String PLAN = """
            {"standalone": "Epic fantasy series with political intrigue",
             "keywordQuery": "epic fantasy political intrigue",
             "variants": ["Fantasy sagas about scheming noble houses", "Court politics in an invented kingdom"],
             "hydePassage": "%s",
             "filters": []}
            """.formatted(HYDE);

    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final SolrClient solrClient = mock(SolrClient.class);

    @BeforeEach
    void setUp() throws Exception {
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<?> texts = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                vectors.add(new float[]{i, 1f});
            }
            return vectors;
        });
        QueryResponse empty = mock(QueryResponse.class);
        when(empty.getResults()).thenReturn(new SolrDocumentList());
        when(solrClient.query(eq("books"), any(SolrParams.class), eq(SolrRequest.METHOD.POST))).thenReturn(empty);
    }

    private List<Query> plannedQueries() {
        ChatModel planner = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(PLAN))));
        return QueryPlanningExpander.builder()
                .plannerChatClient(ChatClient.builder(planner).build())
                .systemPrompt("You are the query planner.")
                .collection("books")
                .variants(2)
                .hydeEnabled(true)
                .embeddingBatcher(new EmbeddingBatcher(embeddingModel))
                .build()
                .expand(Query.builder().text("Recommend an epic fantasy series with political intrigue.")
                        .context(new HashMap<>()).build());
    }

    private HybridDocumentRetriever retriever() {
        SearchRepository repository = new SearchRepository(solrClient, new EmbeddingService(embeddingModel));
        return new HybridDocumentRetriever(repository, "books", 20,
                io.micrometer.observation.ObservationRegistry.NOOP, true);
    }

    @Test
    void threeQueriesOneEmbeddingRequestAndNoSingleEmbeddings() {
        List<Query> queries = plannedQueries();
        HybridDocumentRetriever retriever = retriever();
        queries.forEach(retriever::retrieve);

        assertThat(queries).hasSize(3);
        verify(embeddingModel, times(1)).embed(anyList());
        verify(embeddingModel, never()).embed(anyString());
    }

    @Test
    void theHydePassageIsEmbeddedForTheStandaloneQueryOnly() {
        plannedQueries();

        verify(embeddingModel).embed(List.of(HYDE,
                "Fantasy sagas about scheming noble houses", "Court politics in an invented kingdom"));
    }

    @Test
    void bm25NeverReceivesTheHydePassage() throws Exception {
        List<Query> queries = plannedQueries();
        HybridDocumentRetriever retriever = retriever();
        queries.forEach(retriever::retrieve);

        ArgumentCaptor<SolrParams> params = ArgumentCaptor.forClass(SolrParams.class);
        verify(solrClient, atLeastOnce()).query(eq("books"), params.capture(), eq(SolrRequest.METHOD.POST));
        List<String> keywordQueries = params.getAllValues().stream()
                .filter(p -> "edismax".equals(p.get("defType")))
                .map(p -> p.get("q"))
                .toList();

        assertThat(keywordQueries).containsExactlyInAnyOrder("epic fantasy political intrigue",
                "Fantasy sagas about scheming noble houses", "Court politics in an invented kingdom");
        assertThat(keywordQueries).noneMatch(q -> q.contains("sweeping saga"));
    }
}
