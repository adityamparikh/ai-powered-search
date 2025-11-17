package dev.aparikh.aipoweredsearch.indexing;

import dev.aparikh.aipoweredsearch.indexing.model.BatchIndexRequest;
import dev.aparikh.aipoweredsearch.indexing.model.IndexRequest;
import dev.aparikh.aipoweredsearch.indexing.model.IndexResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for document indexing with vector embeddings.
 *
 * <p>This controller manages document ingestion into the search system, providing
 * endpoints for both single and batch document indexing. Each indexed document
 * is automatically enhanced with vector embeddings for semantic search capabilities.
 *
 * <p>Key features:
 * <ul>
 *   <li>Automatic UUID generation for documents without IDs</li>
 *   <li>Vector embedding generation using OpenAI's text-embedding-3-small model</li>
 *   <li>Support for rich metadata including nested objects and arrays</li>
 *   <li>Efficient batch processing for bulk document ingestion</li>
 * </ul>
 *
 * <p>The indexing process:
 * <ol>
 *   <li>Document content is validated and normalized</li>
 *   <li>OpenAI generates a 1536-dimensional embedding vector</li>
 *   <li>Document and vector are stored in Solr</li>
 *   <li>Document becomes searchable via both keyword and semantic search</li>
 * </ol>
 *
 * @author Aditya Parikh
 * @since 1.0.0
 * @see IndexService
 */
@RestController
@RequestMapping("/api/v1/index")
@Tag(name = "Indexing", description = "API for indexing documents with vector embeddings")
class IndexController {

    private final IndexService indexService;

    /**
     * Constructs a new IndexController with the specified IndexService.
     *
     * @param indexService the service responsible for document indexing operations
     */
    public IndexController(IndexService indexService) {
        this.indexService = indexService;
    }

    /**
     * Indexes a single document with automatic vector embedding generation.
     *
     * <p>This endpoint accepts a document with content and optional metadata,
     * generates vector embeddings using OpenAI, and stores both the document
     * and its vector representation in Solr for hybrid search capabilities.
     *
     * <p>Example request body:
     * <pre>{@code
     * {
     *   "id": "doc123",
     *   "content": "Spring Boot is a framework for building Java applications",
     *   "metadata": {
     *     "author": "John Doe",
     *     "category": "technology",
     *     "tags": ["java", "spring", "programming"]
     *   }
     * }
     * }</pre>
     *
     * @param collection the name of the Solr collection to index into
     * @param indexRequest the document containing content and optional metadata
     * @return an {@link IndexResponse} with the document ID and indexing status
     * @throws IllegalArgumentException if collection or content is null or empty
     */
    @PostMapping("/{collection}")
    @Operation(
            summary = "Index a single document",
            description = "Indexes a document with automatic vector embedding generation using OpenAI. " +
                    "The content will be converted to a 1536-dimensional vector and stored in Solr for semantic search."
    )
    public IndexResponse indexDocument(
            @Parameter(description = "Collection name to index into", required = true, example = "products")
            @PathVariable String collection,
            @Parameter(description = "Document to index with content and optional metadata", required = true)
            @RequestBody IndexRequest indexRequest) {
        return indexService.indexDocument(collection, indexRequest);
    }

    /**
     * Indexes multiple documents in batch with automatic vector embedding generation.
     *
     * <p>This endpoint provides efficient bulk indexing by processing multiple
     * documents in a single request. Each document receives its own embedding
     * vector, and the entire batch is committed to Solr atomically.
     *
     * <p>Benefits over single document indexing:
     * <ul>
     *   <li>Reduced network overhead</li>
     *   <li>Batch embedding generation</li>
     *   <li>Single Solr commit for all documents</li>
     *   <li>Better throughput for large datasets</li>
     * </ul>
     *
     * <p>Example request body:
     * <pre>{@code
     * {
     *   "documents": [
     *     {
     *       "id": "doc1",
     *       "content": "First document content",
     *       "metadata": {"category": "tech"}
     *     },
     *     {
     *       "content": "Second document without ID (auto-generated)",
     *       "metadata": {"category": "science"}
     *     }
     *   ]
     * }
     * }</pre>
     *
     * @param collection the name of the Solr collection to index into
     * @param batchRequest the batch containing multiple documents to index
     * @return an {@link IndexResponse} with document IDs and overall status
     * @throws IllegalArgumentException if collection is null or batch is empty
     */
    @PostMapping("/{collection}/batch")
    @Operation(
            summary = "Batch index multiple documents",
            description = "Indexes multiple documents in batch with automatic vector embedding generation. " +
                    "This is more efficient than indexing documents one at a time."
    )
    public IndexResponse indexDocuments(
            @Parameter(description = "Collection name to index into", required = true, example = "products")
            @PathVariable String collection,
            @Parameter(description = "Batch of documents to index", required = true)
            @RequestBody BatchIndexRequest batchRequest) {
        return indexService.indexDocuments(collection, batchRequest);
    }
}
