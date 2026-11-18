package dev.aparikh.aipoweredsearch.indexing.model;

import dev.aparikh.aipoweredsearch.limits.InputLimit;
import dev.aparikh.aipoweredsearch.limits.WithinLimit;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.util.List;

/**
 * Request model for batch indexing multiple documents with vector embeddings.
 *
 * @param documents List of documents to index, at most {@code search.limits.max-batch-size}
 */
@Schema(description = "Request to index multiple documents in batch with vector embeddings")
public record BatchIndexRequest(
    @Schema(description = "List of documents to index", requiredMode = Schema.RequiredMode.REQUIRED)
        @WithinLimit(InputLimit.BATCH_SIZE)
        List<@Valid IndexRequest> documents) {}
