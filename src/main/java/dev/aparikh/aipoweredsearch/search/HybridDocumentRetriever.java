package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import dev.aparikh.aipoweredsearch.search.rag.RagContextKeys;
import dev.aparikh.aipoweredsearch.search.rag.RagObservations;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Retrieves RAG context using hybrid search — keyword (BM25/edismax) and vector (KNN)
 * results fused with Reciprocal Rank Fusion — instead of vector similarity alone.
 *
 * <p>Vector search finds documents that match what the user <em>meant</em>; keyword search
 * finds documents containing the specific terms they <em>used</em>. Fusing both means the
 * prompt context no longer has to choose between them. Distinctive terminology
 * (a class name, an annotation, an error code) is exactly what pure embedding similarity
 * tends to dilute, and exactly what BM25 is good at.</p>
 *
 * <p>This class adapts {@link SearchRepository#executeHybridRerankSearch} to Spring AI's
 * {@link DocumentRetriever} SPI so it can be plugged into a
 * {@code RetrievalAugmentationAdvisor}.</p>
 *
 * <p><strong>Ordering is significant.</strong> The returned list is ranked by fused RRF
 * score. Any downstream {@code DocumentJoiner} must preserve that order — the default
 * {@code ConcatenationDocumentJoiner} re-sorts by each document's own score and would
 * silently discard the fusion.</p>
 *
 * <p><strong>Per-query inputs.</strong> The retriever reads optional values from
 * {@link Query#context()} (see {@link RagContextKeys}). When {@link RagContextKeys#VECTOR} holds
 * a precomputed {@code float[]} embedding, the vector leg uses it and makes no embedding call;
 * otherwise the query text is embedded exactly as before.</p>
 *
 * <p>Each retrieval is recorded as a {@value RagObservations#RETRIEVE} observation tagged
 * {@code leg=hybrid}.</p>
 *
 * @see SearchRepository#executeHybridRerankSearch
 * @see RrfMerger
 */
@Component
public class HybridDocumentRetriever implements DocumentRetriever {

    /**
     * Fields requested from Solr. Deliberately explicit: the default field list is
     * {@code *}, which returns the 1536-dimension {@code vector} field on every hit —
     * a large, useless payload for RAG context.
     */
    static final String PROJECTED_FIELDS = "id,content,metadata_*";

    private static final String METADATA_PREFIX = "metadata_";
    private static final String ID_FIELD = "id";
    private static final String CONTENT_FIELD = "content";

    /** Fusion bookkeeping from {@link RrfMerger}, carried through for observability. */
    private static final Set<String> RRF_PROVENANCE_FIELDS = Set.of(
            "rrf_score", "keyword_rank", "vector_rank", "keyword_score", "vector_score");

    private static final Logger log = LoggerFactory.getLogger(HybridDocumentRetriever.class);

    static final String HYBRID_LEG = "hybrid";

    private final SearchRepository searchRepository;
    private final String collection;
    private final int topK;
    private final ObservationRegistry observationRegistry;

    /**
     * Creates a retriever bound to a single Solr collection, without observations.
     *
     * <p>For subclasses that do not care about observations, such as the recording retriever in
     * {@code RagAdvisorOrderingIT}. Spring uses the {@code @Autowired} constructor.</p>
     *
     * @see #HybridDocumentRetriever(SearchRepository, String, int, ObservationRegistry)
     */
    public HybridDocumentRetriever(SearchRepository searchRepository, String collection, int topK) {
        this(searchRepository, collection, topK, ObservationRegistry.NOOP);
    }

    /**
     * Creates a retriever bound to a single Solr collection.
     *
     * @param searchRepository    executes the hybrid search
     * @param collection          the Solr collection holding the indexed corpus
     * @param topK                how many fused documents to hand downstream. This is a
     *                            candidate count, not a context size: reranking is expected
     *                            to trim it. With reranking disabled, every one of these
     *                            goes into the prompt, so lower it accordingly.
     * @param observationRegistry records the {@value RagObservations#RETRIEVE} observation
     */
    @Autowired
    public HybridDocumentRetriever(SearchRepository searchRepository,
                                   @Value("${solr.default.collection:books}") String collection,
                                   @Value("${search.rag.hybrid.top-k:20}") int topK,
                                   ObservationRegistry observationRegistry) {
        this.searchRepository = searchRepository;
        this.collection = collection;
        this.topK = topK;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public List<Document> retrieve(Query query) {
        return RagObservations.observe(observationRegistry, RagObservations.RETRIEVE,
                RagObservations.LEG_TAG, HYBRID_LEG, () -> retrieveHybrid(query));
    }

    private List<Document> retrieveHybrid(Query query) {
        // The raw question goes straight to Solr. SearchService.hybridSearch() prepends an
        // LLM call to synthesise Solr query parameters; that is worth it for a search API
        // but is pure latency on a RAG turn, where the model already has the question.
        SearchResponse response = searchRepository.executeHybridRerankSearch(
                collection, query.text(), topK, null, PROJECTED_FIELDS, null, precomputedVector(query));

        List<Document> documents = response.documents().stream()
                .map(this::toDocument)
                .filter(Objects::nonNull)
                .toList();

        log.debug("Hybrid retrieval for '{}' returned {} documents from collection '{}'",
                query.text(), documents.size(), collection);
        return documents;
    }

    /**
     * The embedding an earlier stage already computed for this query, if any.
     *
     * <p>A value that is not a non-empty {@code float[]} is ignored with a warning and the query
     * text is embedded instead, so a contract violation by an earlier stage costs one embedding
     * call rather than a failed kNN query.</p>
     */
    static float @Nullable [] precomputedVector(Query query) {
        Object value = query.context().get(RagContextKeys.VECTOR);
        if (value == null) {
            return null;
        }
        if (value instanceof float[] vector && vector.length > 0) {
            return vector;
        }
        log.warn("Ignoring {} of type {}: expected a non-empty float[]; embedding the query text instead",
                RagContextKeys.VECTOR, value.getClass().getSimpleName());
        return null;
    }

    /**
     * Converts a Solr result row into a Spring AI {@link Document}.
     *
     * @return the converted document, or null if it carries no usable context
     */
    private @Nullable Document toDocument(Map<String, Object> row) {
        Object id = row.get(ID_FIELD);
        Object content = row.get(CONTENT_FIELD);
        if (id == null || content == null) {
            log.debug("Skipping hybrid result without id or content: {}", row.keySet());
            return null;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        row.forEach((field, value) -> {
            if (field.startsWith(METADATA_PREFIX)) {
                metadata.put(field.substring(METADATA_PREFIX.length()), value);
            } else if (RRF_PROVENANCE_FIELDS.contains(field)) {
                // RRF discards raw scores by construction. Carrying the ranks and per-leg
                // scores through makes it possible to answer "why was this chunk in the
                // prompt, and which leg surfaced it?" after the fact.
                metadata.put(field, value);
            }
        });

        return new Document(id.toString(), content.toString(), metadata);
    }
}
