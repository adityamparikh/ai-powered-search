package dev.aparikh.aipoweredsearch.indexing.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Request model for batch indexing multiple documents with vector embeddings.
 *
 * @param documents List of documents to index
 */
@Schema(description = "Request to index multiple documents in batch with vector embeddings")
public record BatchIndexRequest(
        @Schema(description = "List of documents to index", required = true)
        List<IndexRequest> documents
) {
}
