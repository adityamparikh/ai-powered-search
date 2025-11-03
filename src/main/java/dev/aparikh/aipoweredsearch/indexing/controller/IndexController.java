package dev.aparikh.aipoweredsearch.indexing.controller;

import dev.aparikh.aipoweredsearch.indexing.model.BatchIndexRequest;
import dev.aparikh.aipoweredsearch.indexing.model.IndexRequest;
import dev.aparikh.aipoweredsearch.indexing.model.IndexResponse;
import dev.aparikh.aipoweredsearch.indexing.service.IndexService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for document indexing with vector embeddings.
 *
 * <p>Provides endpoints to:</p>
 * <ul>
 *   <li>Index single documents with automatic embedding generation</li>
 *   <li>Batch index multiple documents for improved performance</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/index")
@Tag(name = "Indexing", description = "API for indexing documents with vector embeddings")
class IndexController {

    private final IndexService indexService;

    IndexController(IndexService indexService) {
        this.indexService = indexService;
    }

    /**
     * Indexes a single document with vector embeddings.
     *
     * @param collection   the Solr collection to index into
     * @param indexRequest the document to index
     * @return indexing result with document ID
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
     * Indexes multiple documents in batch with vector embeddings.
     *
     * @param collection   the Solr collection to index into
     * @param batchRequest batch of documents to index
     * @return indexing results with document IDs and status
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
