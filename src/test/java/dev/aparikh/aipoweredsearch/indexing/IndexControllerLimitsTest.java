package dev.aparikh.aipoweredsearch.indexing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.aparikh.aipoweredsearch.indexing.model.BatchIndexRequest;
import dev.aparikh.aipoweredsearch.indexing.model.IndexRequest;
import dev.aparikh.aipoweredsearch.indexing.model.IndexResponse;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * Input limits on the indexing endpoints, at their defaults: a request at a limit is served, one
 * past it gets a 400 problem-details body naming the limit, and nothing is embedded.
 */
@WebMvcTest(IndexController.class)
class IndexControllerLimitsTest {

  private static final int MAX_BATCH_SIZE = 100;
  private static final int MAX_DOCUMENT_LENGTH = 30_000;
  private static final int MAX_BATCH_CONTENT_LENGTH = 150_000;
  private static final int MAX_ID_LENGTH = 512;
  private static final int MAX_METADATA_LENGTH = 10_000;

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private IndexService indexService;

  @BeforeEach
  void stubService() {
    IndexResponse ok = new IndexResponse(1, 0, List.of("doc"), "ok");
    when(indexService.indexDocument(anyString(), any(IndexRequest.class))).thenReturn(ok);
    when(indexService.indexDocuments(anyString(), any(BatchIndexRequest.class))).thenReturn(ok);
  }

  @Test
  void acceptsDocumentAtLengthLimit() throws Exception {
    mockMvc.perform(index(document(MAX_DOCUMENT_LENGTH))).andExpect(status().isOk());
  }

  @Test
  void rejectsDocumentOverLengthLimit() throws Exception {
    mockMvc
        .perform(index(document(MAX_DOCUMENT_LENGTH + 1)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Input limit exceeded"))
        .andExpect(jsonPath("$.errors[0].field").value("content"))
        .andExpect(jsonPath("$.errors[0].limit").value("search.limits.max-document-length"))
        .andExpect(jsonPath("$.errors[0].max").value(MAX_DOCUMENT_LENGTH));
    verifyNoInteractions(indexService);
  }

  @Test
  void acceptsBatchAtSizeLimit() throws Exception {
    mockMvc.perform(batch(documents(MAX_BATCH_SIZE, 10))).andExpect(status().isOk());
  }

  @Test
  void rejectsBatchOverSizeLimit() throws Exception {
    mockMvc
        .perform(batch(documents(MAX_BATCH_SIZE + 1, 10)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.detail")
                .value("documents must be at most 100 documents (search.limits.max-batch-size)"))
        .andExpect(jsonPath("$.errors[0].field").value("documents"))
        .andExpect(jsonPath("$.errors[0].limit").value("search.limits.max-batch-size"))
        .andExpect(jsonPath("$.errors[0].max").value(MAX_BATCH_SIZE));
    verifyNoInteractions(indexService);
  }

  @Test
  void acceptsBatchOfDocumentsAtLengthLimit() throws Exception {
    mockMvc.perform(batch(documents(2, MAX_DOCUMENT_LENGTH))).andExpect(status().isOk());
  }

  @Test
  void rejectsBatchContainingDocumentOverLengthLimit() throws Exception {
    List<IndexRequest> documents =
        List.of(document(10), document(MAX_DOCUMENT_LENGTH + 1), document(10));
    mockMvc
        .perform(batch(documents))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.length()").value(1))
        .andExpect(jsonPath("$.errors[0].field").value("documents[1].content"))
        .andExpect(jsonPath("$.errors[0].limit").value("search.limits.max-document-length"));
    verifyNoInteractions(indexService);
  }

  @Test
  void acceptsBatchAtTotalContentLimit() throws Exception {
    // Five documents of 30,000 code points: 150,000 in total, the default batch content limit.
    mockMvc.perform(batch(documents(5, MAX_DOCUMENT_LENGTH))).andExpect(status().isOk());
  }

  @Test
  void rejectsBatchOverTotalContentLimit() throws Exception {
    List<IndexRequest> documents = new java.util.ArrayList<>(documents(5, MAX_DOCUMENT_LENGTH));
    documents.add(document(1));
    mockMvc
        .perform(batch(documents))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errors.length()").value(1))
        .andExpect(jsonPath("$.errors[0].field").value("documents"))
        .andExpect(jsonPath("$.errors[0].limit").value("search.limits.max-batch-content-length"))
        .andExpect(jsonPath("$.errors[0].max").value(MAX_BATCH_CONTENT_LENGTH))
        .andExpect(
            jsonPath("$.errors[0].message").value("must be at most 150000 characters in total"));
    verifyNoInteractions(indexService);
  }

  @Test
  void acceptsIdAtLimit() throws Exception {
    IndexRequest request = new IndexRequest("i".repeat(MAX_ID_LENGTH), "text", null);
    mockMvc.perform(index(request)).andExpect(status().isOk());
  }

  @Test
  void rejectsIdOverLimit() throws Exception {
    IndexRequest request = new IndexRequest("i".repeat(MAX_ID_LENGTH + 1), "text", null);
    mockMvc
        .perform(index(request))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("id"))
        .andExpect(jsonPath("$.errors[0].limit").value("search.limits.max-id-length"))
        .andExpect(jsonPath("$.errors[0].max").value(MAX_ID_LENGTH));
    verifyNoInteractions(indexService);
  }

  @Test
  void acceptsMetadataAtLimit() throws Exception {
    // The map counts 1, the key 1 and the value 9,998: 10,000 in all.
    mockMvc
        .perform(index(new IndexRequest("doc", "text", metadata(MAX_METADATA_LENGTH - 2))))
        .andExpect(status().isOk());
  }

  @Test
  void rejectsMetadataOverLimit() throws Exception {
    mockMvc
        .perform(index(new IndexRequest("doc", "text", metadata(MAX_METADATA_LENGTH - 1))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("metadata"))
        .andExpect(jsonPath("$.errors[0].limit").value("search.limits.max-metadata-length"))
        .andExpect(jsonPath("$.errors[0].max").value(MAX_METADATA_LENGTH));
    verifyNoInteractions(indexService);
  }

  @Test
  void rejectsBatchDocumentWithOversizedIdOrMetadata() throws Exception {
    List<IndexRequest> documents =
        List.of(
            new IndexRequest("i".repeat(MAX_ID_LENGTH + 1), "text", null),
            new IndexRequest("doc", "text", metadata(MAX_METADATA_LENGTH)));
    mockMvc
        .perform(batch(documents))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.length()").value(2));
    verifyNoInteractions(indexService);
  }

  @Test
  void rejectsNullDocuments() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/index/{collection}/batch", "books")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documents\":null}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid request"))
        .andExpect(jsonPath("$.errors[0].field").value("documents"));
    verifyNoInteractions(indexService);
  }

  private static Map<String, Object> metadata(int valueLength) {
    return Map.of("k", "v".repeat(valueLength));
  }

  private static IndexRequest document(int length) {
    return new IndexRequest(null, "d".repeat(length), Collections.emptyMap());
  }

  private static List<IndexRequest> documents(int count, int length) {
    return IntStream.range(0, count).mapToObj(i -> document(length)).toList();
  }

  private RequestBuilder index(IndexRequest request) {
    return post("/api/v1/index/{collection}", "books")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(request));
  }

  private RequestBuilder batch(List<IndexRequest> documents) {
    return post("/api/v1/index/{collection}/batch", "books")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(new BatchIndexRequest(documents)));
  }
}
