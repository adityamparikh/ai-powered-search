package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    private static final Logger log = LoggerFactory.getLogger(HybridDocumentRetriever.class);

    private final SearchRepository searchRepository;
    private final String collection;
    private final int topK;

    /**
     * Creates a retriever bound to a single Solr collection.
     *
     * @param searchRepository executes the hybrid search
     * @param collection       the Solr collection holding the indexed corpus
     * @param topK             how many fused documents to place in the prompt context
     */
    public HybridDocumentRetriever(SearchRepository searchRepository,
                                   @Value("${solr.default.collection:books}") String collection,
                                   @Value("${search.rag.hybrid.top-k:5}") int topK) {
        this.searchRepository = searchRepository;
        this.collection = collection;
        this.topK = topK;
    }

    @Override
    public List<Document> retrieve(Query query) {
        // The raw question goes straight to Solr. SearchService.hybridSearch() prepends an
        // LLM call to synthesise Solr query parameters; that is worth it for a search API
        // but is pure latency on a RAG turn, where the model already has the question.
        SearchResponse response = searchRepository.executeHybridRerankSearch(
                collection, query.text(), topK, null, PROJECTED_FIELDS, null);

        List<Document> documents = response.documents().stream()
                .map(this::toDocument)
                .filter(Objects::nonNull)
                .toList();

        log.debug("Hybrid retrieval for '{}' returned {} documents from collection '{}'",
                query.text(), documents.size(), collection);
        return documents;
    }

    /**
     * Converts a Solr result row into a Spring AI {@link Document}.
     *
     * @return the converted document, or null if it carries no usable context
     */
    private Document toDocument(Map<String, Object> row) {
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
            }
        });

        return new Document(id.toString(), content.toString(), metadata);
    }
}
