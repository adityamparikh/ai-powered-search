package dev.aparikh.aipoweredsearch.indexing;

import dev.aparikh.aipoweredsearch.indexing.model.BatchIndexRequest;
import dev.aparikh.aipoweredsearch.indexing.model.IndexRequest;
import dev.aparikh.aipoweredsearch.indexing.model.IndexResponse;
import dev.aparikh.aipoweredsearch.solr.vectorstore.VectorStoreFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * Service for indexing documents with vector embeddings.
 *
 * <p>This service handles:
 *
 * <ul>
 *   <li>Converting text content to vector embeddings using OpenAI
 *   <li>Storing documents with embeddings in Solr
 *   <li>Batch indexing for multiple documents
 *   <li>Error handling and reporting
 * </ul>
 */
@Service
public class IndexService {

  private static final Logger log = LoggerFactory.getLogger(IndexService.class);

  private final VectorStoreFactory vectorStoreFactory;

  public IndexService(VectorStoreFactory vectorStoreFactory) {
    this.vectorStoreFactory = vectorStoreFactory;
  }

  /**
   * Indexes a single document with vector embeddings. Embeddings are generated automatically by the
   * VectorStore.
   *
   * @param collection the Solr collection to index into
   * @param indexRequest the document to index
   * @return response with indexing results
   */
  public IndexResponse indexDocument(String collection, IndexRequest indexRequest) {
    String safeCollection = sanitize(collection);
    log.debug("Indexing document in collection: {}", safeCollection);

    try {
      Document document = toDocument(indexRequest);
      vectorStoreFactory.forCollection(collection).add(List.of(document));

      log.info(
          "Successfully indexed document '{}' in collection '{}'",
          document.getId(),
          safeCollection);

      return new IndexResponse(1, 0, List.of(document.getId()), "Successfully indexed document");
    } catch (RuntimeException e) {
      // Reported to the caller in the response rather than as an HTTP error.
      log.error("Failed to index document in collection '{}'", safeCollection, e);
      return new IndexResponse(
          0, 1, Collections.emptyList(), "Failed to index document: " + e.getMessage());
    }
  }

  /**
   * Indexes multiple documents in batch with vector embeddings. Embeddings are generated
   * automatically by the VectorStore.
   *
   * @param collection the Solr collection to index into
   * @param batchRequest the batch of documents to index
   * @return response with indexing results
   */
  public IndexResponse indexDocuments(String collection, BatchIndexRequest batchRequest) {
    String safeCollection = sanitize(collection);
    log.debug(
        "Batch indexing {} documents in collection: {}",
        batchRequest.documents().size(),
        safeCollection);

    List<String> indexedIds = new ArrayList<>();
    int failed = 0;

    try {
      List<Document> documents = new ArrayList<>();
      for (IndexRequest request : batchRequest.documents()) {
        try {
          Document document = toDocument(request);
          documents.add(document);
          indexedIds.add(document.getId());
        } catch (IllegalArgumentException e) {
          // Document.builder() rejects invalid input; skip that document, keep the batch.
          log.error("Failed to prepare document for indexing", e);
          failed++;
        }
      }

      if (!documents.isEmpty()) {
        vectorStoreFactory.forCollection(collection).add(documents);
        log.info(
            "Successfully indexed {} documents in collection '{}'",
            documents.size(),
            safeCollection);
      }

      return new IndexResponse(
          indexedIds.size(),
          failed,
          indexedIds,
          String.format("Successfully indexed %d documents, %d failed", indexedIds.size(), failed));
    } catch (RuntimeException e) {
      log.error("Failed to batch index documents in collection '{}'", safeCollection, e);
      return new IndexResponse(
          indexedIds.size(),
          batchRequest.documents().size() - indexedIds.size(),
          indexedIds,
          "Batch indexing partially failed: " + e.getMessage());
    }
  }

  /**
   * Builds the document to store, with a generated id if none was given. The vector store generates
   * the embedding.
   */
  private static Document toDocument(IndexRequest request) {
    String docId = request.id() != null ? request.id() : UUID.randomUUID().toString();
    Map<String, Object> metadata =
        request.metadata() != null ? new HashMap<>(request.metadata()) : new HashMap<>();
    return Document.builder().id(docId).text(request.content()).metadata(metadata).build();
  }

  /** Strips line breaks and tabs from a caller-supplied value so it cannot forge log lines. */
  private static String sanitize(String value) {
    return value.replaceAll("[\\r\\n\\t]", "_");
  }
}
