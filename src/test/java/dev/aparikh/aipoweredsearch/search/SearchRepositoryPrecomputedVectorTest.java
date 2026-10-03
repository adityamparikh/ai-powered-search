package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.embedding.EmbeddingService;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.SolrParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W5: the vector leg reuses a precomputed embedding instead of calling the embedding model.
 *
 * <p>Uses a real {@link EmbeddingService} over a mocked {@link EmbeddingModel}, so "no embedding
 * request" is checked at the model boundary, where the cost actually is.</p>
 */
@ExtendWith(MockitoExtension.class)
class SearchRepositoryPrecomputedVectorTest {

    private static final String COLLECTION = "books";
    private static final float[] PRECOMPUTED = {0.25f, -0.5f, 0.75f};

    @Mock
    private SolrClient solrClient;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private QueryResponse queryResponse;

    private SearchRepository searchRepository;

    @BeforeEach
    void setUp() throws Exception {
        searchRepository = new SearchRepository(solrClient, new EmbeddingService(embeddingModel));
        SolrDocument doc = new SolrDocument();
        doc.addField("id", "grrm-01");
        doc.addField("content", "A Game of Thrones");
        doc.addField("score", 0.9f);
        SolrDocumentList results = new SolrDocumentList();
        results.add(doc);
        when(queryResponse.getResults()).thenReturn(results);
        when(solrClient.query(eq(COLLECTION), any(SolrParams.class), eq(SolrRequest.METHOD.POST)))
                .thenReturn(queryResponse);
    }

    @Test
    void precomputedVectorIsUsedAndNoEmbeddingRequestIsMade() throws Exception {
        searchRepository.executeHybridRerankSearch(COLLECTION, "game of thrones", 5, null, null, null, PRECOMPUTED);

        verify(embeddingModel, never()).embed(anyString());
        verify(embeddingModel, never()).call(any());
        assertThat(knnQuery()).contains("[0.25, -0.5, 0.75]");
    }

    @Test
    void withoutAPrecomputedVectorTheQueryTextIsEmbeddedAsBefore() throws Exception {
        when(embeddingModel.embed("game of thrones")).thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        searchRepository.executeHybridRerankSearch(COLLECTION, "game of thrones", 5, null, null, null, null);

        verify(embeddingModel, times(1)).embed("game of thrones");
        assertThat(knnQuery()).contains("[0.1, 0.2, 0.3]");
    }

    @Test
    void theSixArgumentOverloadStillEmbedsTheQueryText() throws Exception {
        when(embeddingModel.embed("game of thrones")).thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        searchRepository.executeHybridRerankSearch(COLLECTION, "game of thrones", 5, null, null, null);

        verify(embeddingModel, times(1)).embed("game of thrones");
    }

    @Test
    void vectorOnlyFallbackAlsoReusesThePrecomputedVector() throws Exception {
        // Both hybrid legs come back empty, which drives the keyword -> vector fallback cascade.
        when(queryResponse.getResults()).thenReturn(new SolrDocumentList());

        searchRepository.executeHybridRerankSearch(COLLECTION, "nothing matches", 5, null, null, null, PRECOMPUTED);

        verify(embeddingModel, never()).embed(anyString());
        // hybrid keyword + hybrid vector + fallback keyword + fallback vector
        verify(solrClient, times(4)).query(eq(COLLECTION), any(SolrParams.class), eq(SolrRequest.METHOD.POST));
    }

    private String knnQuery() throws Exception {
        ArgumentCaptor<SolrParams> captor = ArgumentCaptor.forClass(SolrParams.class);
        verify(solrClient, atLeastOnce()).query(eq(COLLECTION), captor.capture(), eq(SolrRequest.METHOD.POST));
        List<String> knn = captor.getAllValues().stream()
                .map(params -> params.get("q"))
                .filter(q -> q != null && q.startsWith("{!knn"))
                .toList();
        assertThat(knn).hasSize(1);
        return knn.getFirst();
    }
}
