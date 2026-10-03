package dev.aparikh.aipoweredsearch.search.rag;

import dev.aparikh.aipoweredsearch.search.RerankingDocumentPostProcessor;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;

import java.util.List;
import java.util.Map;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * W5: per-stage observations are recorded with the expected low-cardinality names and tags.
 */
class RagObservationsTest {

    private final TestObservationRegistry registry = TestObservationRegistry.create();
    private final Query query = new Query("question");
    private final List<Document> documents = List.of(new Document("doc-1", "text", Map.of()));

    @Test
    void postProcessorObservationIsTaggedWithTheProcessorClass() {
        DocumentPostProcessor reranker = new RerankingDocumentPostProcessor(mock(ChatClient.class), 5);

        ObservedDocumentPostProcessor.of(reranker, registry).process(query, List.of());

        assertThat(registry)
                .hasObservationWithNameEqualTo(RagObservations.POSTPROCESS)
                .that()
                .hasLowCardinalityKeyValue(RagObservations.PROCESSOR_TAG, "RerankingDocumentPostProcessor")
                .hasBeenStarted()
                .hasBeenStopped();
    }

    @Test
    void postProcessorResultIsPassedThroughUnchanged() {
        DocumentPostProcessor reverse = (q, docs) -> docs.reversed();
        List<Document> two = List.of(new Document("a", "a", Map.of()), new Document("b", "b", Map.of()));

        List<Document> result = new ObservedDocumentPostProcessor(reverse, registry, "Reverse").process(query, two);

        assertThat(result).extracting(Document::getId).containsExactly("b", "a");
    }

    @Test
    void anonymousSubclassIsTaggedWithItsNamedSuperclass() {
        DocumentPostProcessor anonymous = new RerankingDocumentPostProcessor(mock(ChatClient.class), 5) {
        };

        assertThat(ObservedDocumentPostProcessor.processorName(anonymous.getClass()))
                .isEqualTo("RerankingDocumentPostProcessor");
    }

    @Test
    void lambdaNeedsAnExplicitName() {
        DocumentPostProcessor lambda = (q, docs) -> docs;

        // Its class name (Foo$$Lambda/0x...) is not a stable, meaningful tag value.
        assertThatThrownBy(() -> ObservedDocumentPostProcessor.of(lambda, registry))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anonymousInterfaceImplementationNeedsAnExplicitName() {
        DocumentPostProcessor anonymous = new DocumentPostProcessor() {
            @Override
            public List<Document> process(Query q, List<Document> docs) {
                return docs;
            }
        };

        // Walking up from an anonymous interface implementation reaches Object.
        assertThatThrownBy(() -> ObservedDocumentPostProcessor.of(anonymous, registry))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void postProcessorFailureIsRecordedAndRethrown() {
        DocumentPostProcessor failing = (q, docs) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> new ObservedDocumentPostProcessor(failing, registry, "Failing").process(query, documents))
                .isInstanceOf(IllegalStateException.class);
        assertThat(registry)
                .hasObservationWithNameEqualTo(RagObservations.POSTPROCESS)
                .that()
                .hasError();
    }

    @Test
    void joinerObservationWrapsTheDelegate() {
        DocumentJoiner joiner = documentsForQuery -> documentsForQuery.values().stream()
                .flatMap(List::stream).flatMap(List::stream).toList();

        List<Document> joined = new ObservedDocumentJoiner(joiner, registry).join(Map.of(query, List.of(documents)));

        assertThat(joined).extracting(Document::getId).containsExactly("doc-1");
        assertThat(registry)
                .hasObservationWithNameEqualTo(RagObservations.JOIN)
                .that()
                .hasBeenStarted()
                .hasBeenStopped();
    }

    @Test
    void joinerFailureIsRecordedAndRethrown() {
        IllegalStateException boom = new IllegalStateException("boom");
        DocumentJoiner failing = documentsForQuery -> {
            throw boom;
        };

        assertThatThrownBy(() -> new ObservedDocumentJoiner(failing, registry).join(Map.of(query, List.of(documents))))
                .isSameAs(boom);
        assertThat(registry)
                .hasObservationWithNameEqualTo(RagObservations.JOIN)
                .that()
                .hasError();
    }

    @Test
    void observeWithoutATagReturnsTheValue() {
        assertThat(RagObservations.observe(registry, RagObservations.PLAN, () -> 42)).isEqualTo(42);
        assertThat(registry).hasObservationWithNameEqualTo(RagObservations.PLAN);
    }
}
