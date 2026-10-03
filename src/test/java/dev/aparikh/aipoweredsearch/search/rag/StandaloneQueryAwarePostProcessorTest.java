package dev.aparikh.aipoweredsearch.search.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StandaloneQueryAwarePostProcessorTest {

    private final AtomicReference<Query> seen = new AtomicReference<>();
    private final DocumentPostProcessor recording = (query, documents) -> {
        seen.set(query);
        return documents.reversed();
    };
    private final List<Document> documents = List.of(new Document("a", "a", Map.of()), new Document("b", "b", Map.of()));

    @Test
    void delegateJudgesAgainstTheStandaloneQuery() {
        Query followUp = Query.builder().text("Anything cheaper by the same author?")
                .context(Map.of(RagContextKeys.STANDALONE, "Books by George R.R. Martin cheaper than A Game of Thrones",
                        "other", "kept"))
                .build();

        List<Document> result = new StandaloneQueryAwarePostProcessor(recording).process(followUp, documents);

        assertThat(seen.get().text()).isEqualTo("Books by George R.R. Martin cheaper than A Game of Thrones");
        assertThat(seen.get().context()).containsEntry("other", "kept");
        assertThat(result).extracting(Document::getId).containsExactly("b", "a");
    }

    @Test
    void withoutAStandaloneQueryTheOriginalIsPassedThrough() {
        Query original = new Query("A Clash of Kings");

        new StandaloneQueryAwarePostProcessor(recording).process(original, documents);

        assertThat(seen.get()).isSameAs(original);
    }

    @Test
    void blankOrNonStringStandaloneValuesAreIgnored() {
        Query blank = Query.builder().text("q").context(Map.of(RagContextKeys.STANDALONE, "  ")).build();
        Query wrongType = Query.builder().text("q").context(Map.of(RagContextKeys.STANDALONE, 42)).build();

        new StandaloneQueryAwarePostProcessor(recording).process(blank, documents);
        assertThat(seen.get()).isSameAs(blank);
        new StandaloneQueryAwarePostProcessor(recording).process(wrongType, documents);
        assertThat(seen.get()).isSameAs(wrongType);
    }

    @Test
    void exposesItsDelegate() {
        assertThat(new StandaloneQueryAwarePostProcessor(recording).delegate()).isSameAs(recording);
    }
}
