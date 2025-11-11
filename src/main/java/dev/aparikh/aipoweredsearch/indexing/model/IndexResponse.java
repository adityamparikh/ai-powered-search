package dev.aparikh.aipoweredsearch.indexing.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response model for document indexing operations.
 *
 * @param indexed     Number of documents successfully indexed
 * @param failed      Number of documents that failed to index
 * @param documentIds List of document IDs that were indexed
 * @param message     Status message
 */
@Schema(description = "Response for document indexing operations")
public record IndexResponse(
        @Schema(description = "Number of documents successfully indexed", example = "5")
        int indexed,

        @Schema(description = "Number of documents that failed to index", example = "0")
        int failed,

        @Schema(description = "List of document IDs that were indexed")
        List<String> documentIds,

        @Schema(description = "Status message", example = "Successfully indexed 5 documents")
        String message
) {
}
