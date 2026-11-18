package dev.aparikh.aipoweredsearch.indexing.model;

import dev.aparikh.aipoweredsearch.limits.InputLimit;
import dev.aparikh.aipoweredsearch.limits.WithinLimit;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Request model for batch indexing multiple documents with vector embeddings.
 *
 * @param documents List of documents to index: at most {@code search.limits.max-batch-size} of
 *     them, and at most {@code search.limits.max-batch-content-length} characters of content in
 *     total, since the whole batch is embedded in one OpenAI request
 */
@Schema(description = "Request to index multiple documents in batch with vector embeddings")
public record BatchIndexRequest(
    @Schema(description = "List of documents to index", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @WithinLimit(InputLimit.BATCH_SIZE)
        @WithinLimit(InputLimit.BATCH_CONTENT_LENGTH)
        List<@Valid IndexRequest> documents) {}
