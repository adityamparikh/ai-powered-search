package dev.aparikh.aipoweredsearch.indexing.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Request model for indexing a single document with vector embeddings.
 *
 * @param id       Unique document identifier (optional - will be generated if not provided)
 * @param content  The text content to be embedded and indexed
 * @param metadata Additional metadata fields to store with the document
 */
@Schema(description = "Request to index a single document with vector embeddings")
public record IndexRequest(
        @Schema(description = "Unique document identifier (auto-generated if not provided)", example = "doc-001")
        String id,

        @Schema(description = "Text content to be embedded and indexed", example = "Spring Boot is a powerful framework for building Java applications", required = true)
        String content,

        @Schema(description = "Additional metadata fields", example = "{\"category\": \"framework\", \"tags\": [\"java\", \"spring\"]}")
        Map<String, Object> metadata
) {
}
