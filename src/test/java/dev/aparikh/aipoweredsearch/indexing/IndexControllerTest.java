package dev.aparikh.aipoweredsearch.indexing;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aparikh.aipoweredsearch.indexing.model.BatchIndexRequest;
import dev.aparikh.aipoweredsearch.indexing.model.IndexRequest;
import dev.aparikh.aipoweredsearch.indexing.model.IndexResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for IndexController using @WebMvcTest.
 * Tests the REST API layer in isolation without starting the full application.
 */
@WebMvcTest(IndexController.class)
class IndexControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private IndexService indexService;

    @Test
    void shouldIndexSingleDocument() throws Exception {
        // Given
        String collection = "test-collection";
        IndexRequest request = new IndexRequest(
                "doc-1",
                "Test document content",
                Map.of("category", "test")
        );

        IndexResponse mockResponse = new IndexResponse(
                1,
                0,
                List.of("doc-1"),
                "Successfully indexed document"
        );

        when(indexService.indexDocument(eq(collection), any(IndexRequest.class)))
                .thenReturn(mockResponse);

        // When/Then
        mockMvc.perform(post("/api/v1/index/{collection}", collection)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(1))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.documentIds[0]").value("doc-1"))
                .andExpect(jsonPath("$.message").value("Successfully indexed document"));

        verify(indexService).indexDocument(eq(collection), any(IndexRequest.class));
    }

    @Test
    void shouldIndexSingleDocumentWithoutId() throws Exception {
        // Given
        String collection = "test-collection";
        IndexRequest request = new IndexRequest(
                null,
                "Test document content without ID",
                Map.of("category", "test")
        );

        String generatedId = "generated-uuid-123";
        IndexResponse mockResponse = new IndexResponse(
                1,
                0,
                List.of(generatedId),
                "Successfully indexed document"
        );

        when(indexService.indexDocument(eq(collection), any(IndexRequest.class)))
                .thenReturn(mockResponse);

        // When/Then
        mockMvc.perform(post("/api/v1/index/{collection}", collection)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(1))
                .andExpect(jsonPath("$.documentIds[0]").value(generatedId));
    }

    @Test
    void shouldIndexSingleDocumentWithNullMetadata() throws Exception {
        // Given
        String collection = "test-collection";
        IndexRequest request = new IndexRequest(
                "doc-2",
                "Test document without metadata",
                null
        );

        IndexResponse mockResponse = new IndexResponse(
                1,
                0,
                List.of("doc-2"),
                "Successfully indexed document"
        );

        when(indexService.indexDocument(eq(collection), any(IndexRequest.class)))
                .thenReturn(mockResponse);

        // When/Then
        mockMvc.perform(post("/api/v1/index/{collection}", collection)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(1))
                .andExpect(jsonPath("$.documentIds[0]").value("doc-2"));
    }

    @Test
    void shouldBatchIndexMultipleDocuments() throws Exception {
        // Given
        String collection = "test-collection";
        List<IndexRequest> documents = List.of(
                new IndexRequest("doc-1", "First document", Map.of("order", 1)),
                new IndexRequest("doc-2", "Second document", Map.of("order", 2)),
                new IndexRequest("doc-3", "Third document", Map.of("order", 3))
        );
        BatchIndexRequest batchRequest = new BatchIndexRequest(documents);

        IndexResponse mockResponse = new IndexResponse(
                3,
                0,
                List.of("doc-1", "doc-2", "doc-3"),
                "Successfully indexed 3 documents, 0 failed"
        );

        when(indexService.indexDocuments(eq(collection), any(BatchIndexRequest.class)))
                .thenReturn(mockResponse);

        // When/Then
        mockMvc.perform(post("/api/v1/index/{collection}/batch", collection)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(3))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.documentIds").isArray())
                .andExpect(jsonPath("$.documentIds.length()").value(3))
                .andExpect(jsonPath("$.documentIds[0]").value("doc-1"))
                .andExpect(jsonPath("$.documentIds[1]").value("doc-2"))
                .andExpect(jsonPath("$.documentIds[2]").value("doc-3"))
                .andExpect(jsonPath("$.message").value("Successfully indexed 3 documents, 0 failed"));

        verify(indexService).indexDocuments(eq(collection), any(BatchIndexRequest.class));
    }

    @Test
    void shouldBatchIndexEmptyList() throws Exception {
        // Given
        String collection = "test-collection";
        BatchIndexRequest batchRequest = new BatchIndexRequest(Collections.emptyList());

        IndexResponse mockResponse = new IndexResponse(
                0,
                0,
                Collections.emptyList(),
                "Successfully indexed 0 documents, 0 failed"
        );

        when(indexService.indexDocuments(eq(collection), any(BatchIndexRequest.class)))
                .thenReturn(mockResponse);

        // When/Then
        mockMvc.perform(post("/api/v1/index/{collection}/batch", collection)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(0))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.documentIds").isEmpty());
    }

    @Test
    void shouldHandlePartialBatchFailure() throws Exception {
        // Given
        String collection = "test-collection";
        List<IndexRequest> documents = List.of(
                new IndexRequest("doc-1", "First document", null),
                new IndexRequest("doc-2", "Second document", null),
                new IndexRequest("doc-3", "Third document", null)
        );
        BatchIndexRequest batchRequest = new BatchIndexRequest(documents);

        // Simulate partial failure
        IndexResponse mockResponse = new IndexResponse(
                2,
                1,
                List.of("doc-1", "doc-2"),
                "Successfully indexed 2 documents, 1 failed"
        );

        when(indexService.indexDocuments(eq(collection), any(BatchIndexRequest.class)))
                .thenReturn(mockResponse);

        // When/Then
        mockMvc.perform(post("/api/v1/index/{collection}/batch", collection)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(2))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.documentIds.length()").value(2));
    }

    @Test
    void shouldHandleIndexingFailure() throws Exception {
        // Given
        String collection = "test-collection";
        IndexRequest request = new IndexRequest(
                "doc-fail",
                "This document will fail",
                null
        );

        IndexResponse mockResponse = new IndexResponse(
                0,
                1,
                Collections.emptyList(),
                "Failed to index document: Connection error"
        );

        when(indexService.indexDocument(eq(collection), any(IndexRequest.class)))
                .thenReturn(mockResponse);

        // When/Then
        mockMvc.perform(post("/api/v1/index/{collection}", collection)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(0))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.documentIds").isEmpty())
                .andExpect(jsonPath("$.message").value("Failed to index document: Connection error"));
    }

    @Test
    void shouldReturnBadRequestForInvalidJson() throws Exception {
        // When/Then
        mockMvc.perform(post("/api/v1/index/test-collection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid json}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnBadRequestForMissingContentType() throws Exception {
        // Given
        IndexRequest request = new IndexRequest("doc-1", "content", null);

        // When/Then
        mockMvc.perform(post("/api/v1/index/test-collection")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void shouldIndexDocumentWithComplexMetadata() throws Exception {
        // Given
        String collection = "test-collection";
        Map<String, Object> complexMetadata = Map.of(
                "author", "John Doe",
                "tags", List.of("java", "spring", "ai"),
                "priority", 5,
                "published", true,
                "rating", 4.5
        );

        IndexRequest request = new IndexRequest(
                "complex-doc",
                "Document with complex metadata",
                complexMetadata
        );

        IndexResponse mockResponse = new IndexResponse(
                1,
                0,
                List.of("complex-doc"),
                "Successfully indexed document"
        );

        when(indexService.indexDocument(eq(collection), any(IndexRequest.class)))
                .thenReturn(mockResponse);

        // When/Then
        mockMvc.perform(post("/api/v1/index/{collection}", collection)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(1))
                .andExpect(jsonPath("$.documentIds[0]").value("complex-doc"));
    }

    @Test
    void shouldHandleDifferentCollectionNames() throws Exception {
        // Given
        String collection1 = "products";
        String collection2 = "articles";

        IndexRequest request = new IndexRequest("doc-1", "content", null);

        IndexResponse mockResponse = new IndexResponse(
                1,
                0,
                List.of("doc-1"),
                "Successfully indexed document"
        );

        when(indexService.indexDocument(any(String.class), any(IndexRequest.class)))
                .thenReturn(mockResponse);

        // When/Then - Test first collection
        mockMvc.perform(post("/api/v1/index/{collection}", collection1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(indexService).indexDocument(eq(collection1), any(IndexRequest.class));

        // When/Then - Test second collection
        mockMvc.perform(post("/api/v1/index/{collection}", collection2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(indexService).indexDocument(eq(collection2), any(IndexRequest.class));
    }

    @Test
    void shouldHandleSpecialCharactersInContent() throws Exception {
        // Given
        String collection = "test-collection";
        IndexRequest request = new IndexRequest(
                "special-chars",
                "Content with special chars: @#$%^&*(){}[]|\\;:'\"<>?,./~`",
                Map.of("type", "special")
        );

        IndexResponse mockResponse = new IndexResponse(
                1,
                0,
                List.of("special-chars"),
                "Successfully indexed document"
        );

        when(indexService.indexDocument(eq(collection), any(IndexRequest.class)))
                .thenReturn(mockResponse);

        // When/Then
        mockMvc.perform(post("/api/v1/index/{collection}", collection)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(1));
    }

    @Test
    void shouldHandleUnicodeContent() throws Exception {
        // Given
        String collection = "test-collection";
        IndexRequest request = new IndexRequest(
                "unicode-doc",
                "Unicode: 你好世界 🌍 Здравствуй мир مرحبا بالعالم",
                Map.of("language", "multi")
        );

        IndexResponse mockResponse = new IndexResponse(
                1,
                0,
                List.of("unicode-doc"),
                "Successfully indexed document"
        );

        when(indexService.indexDocument(eq(collection), any(IndexRequest.class)))
                .thenReturn(mockResponse);

        // When/Then
        mockMvc.perform(post("/api/v1/index/{collection}", collection)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(1));
    }

    @Test
    void shouldHandleLargeContent() throws Exception {
        // Given
        String collection = "test-collection";
        StringBuilder largeContent = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            largeContent.append("This is line ").append(i).append(" of a large document. ");
        }

        IndexRequest request = new IndexRequest(
                "large-doc",
                largeContent.toString(),
                Map.of("size", "large")
        );

        IndexResponse mockResponse = new IndexResponse(
                1,
                0,
                List.of("large-doc"),
                "Successfully indexed document"
        );

        when(indexService.indexDocument(eq(collection), any(IndexRequest.class)))
                .thenReturn(mockResponse);

        // When/Then
        mockMvc.perform(post("/api/v1/index/{collection}", collection)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(1));
    }

    @Test
    void shouldVerifyContentTypeIsJson() throws Exception {
        // Given
        String collection = "test-collection";
        IndexRequest request = new IndexRequest("doc-1", "content", null);

        // When/Then - Correct content type
        mockMvc.perform(post("/api/v1/index/{collection}", collection)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        // When/Then - Wrong content type
        mockMvc.perform(post("/api/v1/index/{collection}", collection)
                        .contentType(MediaType.APPLICATION_XML)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnsupportedMediaType());
    }
}
