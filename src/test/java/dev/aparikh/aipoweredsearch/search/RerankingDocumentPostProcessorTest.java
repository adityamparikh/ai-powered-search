package dev.aparikh.aipoweredsearch.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RerankingDocumentPostProcessor}.
 *
 * <p>Reranking is an optimisation layered on top of retrieval, so the failure modes matter
 * more than the happy path: a bad or unavailable ranking must degrade to the retriever's
 * own order, never fail the request or corrupt the context.</p>
 */
@ExtendWith(MockitoExtension.class)
class RerankingDocumentPostProcessorTest {

    private static final int TOP_K = 3;

    @Mock
    private ChatClient chatClient;

    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callResponseSpec;

    private RerankingDocumentPostProcessor processor;

    @BeforeEach
    void setUp() {
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class, org.mockito.Mockito.RETURNS_SELF);
        callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        lenient().when(chatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.user(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(callResponseSpec);

        processor = new RerankingDocumentPostProcessor(chatClient, TOP_K);
    }

    private static List<Document> docs(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new Document("doc-" + i, "content " + i, Map.of()))
                .toList();
    }

    private void stubRanking(Integer... indexes) {
        when(callResponseSpec.entity(RerankingDocumentPostProcessor.Ranking.class))
                .thenReturn(new RerankingDocumentPostProcessor.Ranking(List.of(indexes)));
    }

    private static List<String> ids(List<Document> documents) {
        return documents.stream().map(Document::getId).toList();
    }

    @Test
    void reordersDocumentsToMatchTheModelRanking() {
        stubRanking(2, 0, 1);

        List<Document> result = processor.process(Query.builder().text("q").build(), docs(3));

        assertThat(ids(result)).containsExactly("doc-2", "doc-0", "doc-1");
    }

    @Test
    void keepsOnlyTopKDocuments() {
        stubRanking(4, 3, 2, 1, 0);

        List<Document> result = processor.process(Query.builder().text("q").build(), docs(5));

        assertThat(result).hasSize(TOP_K);
        assertThat(ids(result)).containsExactly("doc-4", "doc-3", "doc-2");
    }

    @Test
    void discardsOutOfRangeIndexesRatherThanThrowing() {
        // An index past the end would blow up List.get and fail the whole RAG request.
        stubRanking(1, 99, -1, 0);

        List<Document> result = processor.process(Query.builder().text("q").build(), docs(3));

        assertThat(ids(result)).containsExactly("doc-1", "doc-0");
    }

    @Test
    void discardsDuplicateIndexesSoNoChunkAppearsTwice() {
        // A repeated index would put the same chunk in the prompt more than once.
        stubRanking(1, 1, 0);

        List<Document> result = processor.process(Query.builder().text("q").build(), docs(3));

        assertThat(ids(result)).containsExactly("doc-1", "doc-0");
    }

    @Test
    void fallsBackToRetrievalOrderWhenTheModelReturnsNoUsableIndexes() {
        stubRanking();

        List<Document> result = processor.process(Query.builder().text("q").build(), docs(5));

        assertThat(ids(result)).containsExactly("doc-0", "doc-1", "doc-2");
    }

    @Test
    void fallsBackToRetrievalOrderWhenTheModelCallFails() {
        when(callResponseSpec.entity(RerankingDocumentPostProcessor.Ranking.class))
                .thenThrow(new RuntimeException("model unavailable"));

        List<Document> result = processor.process(Query.builder().text("q").build(), docs(5));

        assertThat(ids(result)).containsExactly("doc-0", "doc-1", "doc-2");
    }

    @Test
    void doesNotCallTheModelWhenThereIsNothingToRerank() {
        List<Document> result = processor.process(Query.builder().text("q").build(), List.of());

        assertThat(result).isEmpty();
        verify(chatClient, never()).prompt();
    }

    @Test
    void includesTheQueryAndEveryCandidateInTheRankingPrompt() {
        stubRanking(0);

        processor.process(Query.builder().text("how do I bind config?").build(), docs(2));

        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(prompt.capture());
        assertThat(prompt.getValue())
                .contains("how do I bind config?")
                .contains("content 0")
                .contains("content 1");
    }
}
