package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import dev.aparikh.aipoweredsearch.search.rag.RagContextKeys;
import dev.aparikh.aipoweredsearch.search.rag.RagObservations;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
 * <p><strong>Two modes.</strong> In <em>unfused</em> mode (the default, used with
 * {@code RrfDocumentJoiner}) the BM25 and kNN legs run concurrently and their hits are returned
 * <em>unfused</em>, each tagged in metadata with {@link RagContextKeys#LEG} ({@code keyword} or
 * {@code vector}) and its 1-based {@link RagContextKeys#LEG_RANK}. The joiner then fuses every leg
 * of every query in one RRF pass, rather than fusing per query and concatenating. In
 * <em>fused</em> mode ({@code search.rag.fusion.enabled=false}) the retriever returns
 * {@link SearchRepository#executeHybridRerankSearch}'s RRF-fused list, as it always did.</p>
 *
 * <p><strong>Fallbacks in unfused mode.</strong> A leg that fails is logged at WARN and contributes
 * nothing, and the other leg's hits are returned alone. This maps onto
 * {@code executeHybridRerankSearch}'s cascade (hybrid → keyword-only → vector-only): when the
 * vector leg fails it falls back to keyword-only results, when the keyword leg fails to
 * vector-only results, and when both legs are empty so is the cascade. After the joiner caps the
 * fused list at {@code search.rag.fusion.top-k}, the documents and their order are the same as the
 * cascade's.</p>
 *
 * <p>Each retrieval is recorded as a {@value RagObservations#RETRIEVE} observation: one per leg
 * ({@code leg=keyword}, {@code leg=vector}) in unfused mode, one {@code leg=hybrid} in fused
 * mode.</p>
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
    static final String KEYWORD_LEG = "keyword";
    static final String VECTOR_LEG = "vector";

    private static final String SCORE_FIELD = "score";

    private final SearchRepository searchRepository;
    private final String collection;
    private final int topK;
    private final ObservationRegistry observationRegistry;
    private final boolean unfused;
    private final ContextSnapshotFactory contextSnapshotFactory = ContextSnapshotFactory.builder().build();

    /**
     * Creates a fused-mode retriever bound to a single Solr collection, without observations.
     *
     * <p>For subclasses that do not care about observations, such as the recording retriever in
     * {@code RagAdvisorOrderingIT}. Spring uses the {@code @Autowired} constructor.</p>
     *
     * @see #HybridDocumentRetriever(SearchRepository, String, int, ObservationRegistry, boolean)
     */
    public HybridDocumentRetriever(SearchRepository searchRepository, String collection, int topK) {
        this(searchRepository, collection, topK, ObservationRegistry.NOOP, false);
    }

    /**
     * Creates a fused-mode retriever bound to a single Solr collection.
     *
     * @see #HybridDocumentRetriever(SearchRepository, String, int, ObservationRegistry, boolean)
     */
    public HybridDocumentRetriever(SearchRepository searchRepository, String collection, int topK,
                                   ObservationRegistry observationRegistry) {
        this(searchRepository, collection, topK, observationRegistry, false);
    }

    /**
     * Creates a retriever bound to a single Solr collection.
     *
     * @param searchRepository    executes the searches
     * @param collection          the Solr collection holding the indexed corpus
     * @param topK                how many fused documents to hand downstream. This is a
     *                            candidate count, not a context size: reranking is expected
     *                            to trim it. With reranking disabled, every one of these
     *                            goes into the prompt, so lower it accordingly. In unfused mode
     *                            each leg fetches {@code 2 * topK} hits and the joiner applies
     *                            its own cap.
     * @param observationRegistry records the {@value RagObservations#RETRIEVE} observations
     * @param unfused             true to return each leg's hits unfused, tagged for
     *                            {@code RrfDocumentJoiner}; false to return the RRF-fused list
     */
    @Autowired
    public HybridDocumentRetriever(SearchRepository searchRepository,
                                   @Value("${solr.default.collection:books}") String collection,
                                   @Value("${search.rag.hybrid.top-k:20}") int topK,
                                   ObservationRegistry observationRegistry,
                                   @Value("${search.rag.fusion.enabled:true}") boolean unfused) {
        this.searchRepository = searchRepository;
        this.collection = collection;
        this.topK = topK;
        this.observationRegistry = observationRegistry;
        this.unfused = unfused;
    }

    @Override
    public List<Document> retrieve(Query query) {
        if (unfused) {
            return retrieveLegs(query);
        }
        return RagObservations.observe(observationRegistry, RagObservations.RETRIEVE,
                RagObservations.LEG_TAG, HYBRID_LEG, () -> retrieveHybrid(query));
    }

    /**
     * Unfused mode: runs both legs concurrently and returns their hits, keyword first, each
     * tagged with its leg and leg rank.
     */
    private List<Document> retrieveLegs(Query query) {
        int fetchSize = topK * SearchRepository.OVER_FETCH_MULTIPLIER;
        // The legs run on fresh virtual threads; the snapshot carries the current observation
        // across, so each leg's rag.retrieve span nests under the request.
        ContextSnapshot snapshot = contextSnapshotFactory.captureAll();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<Document>> keyword = executor.submit(snapshot.wrap(observedLeg(KEYWORD_LEG, () ->
                    searchRepository.executeKeywordSearch(collection, query.text(), fetchSize, null, PROJECTED_FIELDS))));
            Future<List<Document>> vector = executor.submit(snapshot.wrap(observedLeg(VECTOR_LEG, () ->
                    searchRepository.executeVectorSearch(collection, query.text(), fetchSize, null, PROJECTED_FIELDS,
                            precomputedVector(query)))));

            List<Document> documents = new ArrayList<>(awaitLeg(KEYWORD_LEG, keyword, query));
            documents.addAll(awaitLeg(VECTOR_LEG, vector, query));
            log.debug("Unfused retrieval for '{}' returned {} hits from collection '{}'",
                    query.text(), documents.size(), collection);
            return documents;
        }
    }

    /** Wraps one leg's search in a {@value RagObservations#RETRIEVE} observation and tags its hits. */
    private Callable<List<Document>> observedLeg(String leg, Callable<List<Map<String, Object>>> search) {
        return () -> RagObservations.observe(observationRegistry, RagObservations.RETRIEVE,
                RagObservations.LEG_TAG, leg, () -> {
                    try {
                        return toLegDocuments(leg, search.call());
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(leg + " leg failed", e);
                    }
                });
    }

    private List<Document> awaitLeg(String leg, Future<List<Document>> future, Query query) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            // A cancelled caller: restore the flag and propagate rather than return partial results.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Retrieval was interrupted", e);
        } catch (ExecutionException e) {
            log.warn("The {} retrieval leg failed for '{}'; continuing with the other leg: {}",
                    leg, query.text(), String.valueOf(e.getCause()));
            return List.of();
        }
    }

    private List<Document> toLegDocuments(String leg, List<Map<String, Object>> rows) {
        List<Document> documents = new ArrayList<>(rows.size());
        int rank = 0;
        for (Map<String, Object> row : rows) {
            Document document = toDocument(row);
            if (document == null) {
                continue;
            }
            rank++;
            Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
            metadata.put(RagContextKeys.LEG, leg);
            metadata.put(RagContextKeys.LEG_RANK, rank);
            if (row.get(SCORE_FIELD) instanceof Number score) {
                metadata.put(leg + "_score", score.doubleValue());
            }
            documents.add(new Document(Objects.requireNonNull(document.getId()),
                    Objects.requireNonNull(document.getText()), metadata));
        }
        return documents;
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
