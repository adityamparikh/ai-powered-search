package dev.aparikh.aipoweredsearch.search.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W2: {@link LegRouting} decides each leg's input; {@link EmbeddingBatcher} embeds all of them at once.
 */
class LegRoutingAndBatchingTest {

    private static final String TEXT = "Recommend an epic fantasy series with political intrigue";
    private static final String KEYWORDS = "epic fantasy political intrigue";
    private static final String HYDE = "Rival noble houses scheme for a contested throne.";
    private static final float[] VECTOR = {0.1f, 0.2f};

    private static Query query(Map<String, Object> context) {
        return Query.builder().text(TEXT).context(new HashMap<>(context)).build();
    }

    /** Every combination of the three context keys: what each leg must search with. */
    static Stream<Arguments> routingMatrix() {
        return Stream.of(
                Arguments.of(Map.of(), TEXT, TEXT, false),
                Arguments.of(Map.of(RagContextKeys.KEYWORD_QUERY, KEYWORDS), KEYWORDS, TEXT, false),
                Arguments.of(Map.of(RagContextKeys.VECTOR_TEXT, HYDE), TEXT, HYDE, false),
                Arguments.of(Map.of(RagContextKeys.VECTOR, VECTOR), TEXT, TEXT, true),
                Arguments.of(Map.of(RagContextKeys.KEYWORD_QUERY, KEYWORDS, RagContextKeys.VECTOR_TEXT, HYDE), KEYWORDS, HYDE, false),
                Arguments.of(Map.of(RagContextKeys.KEYWORD_QUERY, KEYWORDS, RagContextKeys.VECTOR, VECTOR), KEYWORDS, TEXT, true),
                Arguments.of(Map.of(RagContextKeys.VECTOR_TEXT, HYDE, RagContextKeys.VECTOR, VECTOR), TEXT, HYDE, true),
                Arguments.of(Map.of(RagContextKeys.KEYWORD_QUERY, KEYWORDS, RagContextKeys.VECTOR_TEXT, HYDE,
                        RagContextKeys.VECTOR, VECTOR), KEYWORDS, HYDE, true),
                // blank or wrongly typed values fall back
                Arguments.of(Map.of(RagContextKeys.KEYWORD_QUERY, " ", RagContextKeys.VECTOR_TEXT, 42,
                        RagContextKeys.VECTOR, List.of(0.1f)), TEXT, TEXT, false));
    }

    @ParameterizedTest
    @MethodSource("routingMatrix")
    void eachLegGetsItsOwnInputWithFallbacks(Map<String, Object> context, String keyword, String vectorText,
                                             boolean precomputed) {
        Query query = query(context);

        assertThat(LegRouting.keywordText(query)).isEqualTo(keyword);
        assertThat(LegRouting.vectorText(query)).isEqualTo(vectorText);
        assertThat(LegRouting.vector(query)).isEqualTo(precomputed ? VECTOR : null);
        // The BM25 leg never sees the HyDE passage, whatever else is set.
        assertThat(LegRouting.keywordText(query)).isNotEqualTo(HYDE);
    }

    @Test
    void batcherMakesOneRequestForAllQueriesUsingTheirVectorText() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenReturn(List.of(new float[]{1f}, new float[]{2f}, new float[]{3f}));
        List<Query> queries = List.of(
                query(Map.of(RagContextKeys.VECTOR_TEXT, HYDE)),
                Query.builder().text("variant one").context(new HashMap<>()).build(),
                Query.builder().text("variant two").context(new HashMap<>()).build());

        new EmbeddingBatcher(model).embed(queries);

        verify(model, times(1)).embed(List.of(HYDE, "variant one", "variant two"));
        assertThat(queries).extracting(LegRouting::vector)
                .containsExactly(new float[]{1f}, new float[]{2f}, new float[]{3f});
    }

    @Test
    void batcherFailureLeavesVectorsUnsetSoLegsEmbedThemselves() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenThrow(new IllegalStateException("rate limited"));
        List<Query> queries = List.of(query(Map.of()), query(Map.of()));

        new EmbeddingBatcher(model).embed(queries);

        assertThat(queries).allSatisfy(q -> assertThat(LegRouting.vector(q)).isNull());
    }

    @Test
    void batcherIgnoresAResponseOfTheWrongSize() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenReturn(List.of(new float[]{1f}));
        List<Query> queries = List.of(query(Map.of()), query(Map.of()));

        new EmbeddingBatcher(model).embed(queries);

        assertThat(queries).allSatisfy(q -> assertThat(LegRouting.vector(q)).isNull());
    }

    @Test
    void batcherDoesNothingForNoQueries() {
        EmbeddingModel model = mock(EmbeddingModel.class);

        new EmbeddingBatcher(model).embed(List.of());

        verify(model, never()).embed(anyList());
    }
}
