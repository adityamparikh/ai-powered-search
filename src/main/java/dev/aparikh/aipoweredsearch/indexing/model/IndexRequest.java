package dev.aparikh.aipoweredsearch.indexing.model;

import dev.aparikh.aipoweredsearch.limits.EmbeddedContent;
import dev.aparikh.aipoweredsearch.limits.InputLimit;
import dev.aparikh.aipoweredsearch.limits.WithinLimit;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Request model for indexing a single document with vector embeddings.
 *
 * @param id Unique document identifier (optional - will be generated if not provided), at most
 *     {@code search.limits.max-id-length} characters
 * @param content The text content to be embedded and indexed, at most {@code
 *     search.limits.max-document-length} characters
 * @param metadata Additional metadata fields to store with the document, at most {@code
 *     search.limits.max-metadata-length} characters of keys and values
 */
@Schema(description = "Request to index a single document with vector embeddings")
public record IndexRequest(
    @Schema(
            description = "Unique document identifier (auto-generated if not provided)",
            example = "doc-001")
        @WithinLimit(InputLimit.ID_LENGTH)
        @Nullable String id,
    @Schema(
            description = "Text content to be embedded and indexed",
            example = "Spring Boot is a powerful framework for building Java applications",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @WithinLimit(InputLimit.DOCUMENT_LENGTH)
        String content,
    @Schema(
            description = "Additional metadata fields",
            example = "{\"category\": \"framework\", \"tags\": [\"java\", \"spring\"]}")
        @WithinLimit(InputLimit.METADATA_LENGTH)
        @Nullable Map<String, Object> metadata)
    implements EmbeddedContent {}
