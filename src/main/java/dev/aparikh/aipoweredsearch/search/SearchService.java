package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.search.model.AskRequest;
import dev.aparikh.aipoweredsearch.search.model.AskResponse;
import dev.aparikh.aipoweredsearch.search.model.FieldInfo;
import dev.aparikh.aipoweredsearch.search.model.QueryGenerationResponse;
import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import dev.aparikh.aipoweredsearch.solr.vectorstore.VectorStoreFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Service layer for AI-enhanced search operations.
 *
 * <p>This service orchestrates various search strategies including:
 *
 * <ul>
 *   <li>Traditional keyword search with AI-powered query generation
 *   <li>Semantic vector search using embeddings and similarity matching
 *   <li>Hybrid search combining lexical and semantic search using RRF (Reciprocal Rank Fusion)
 *   <li>RAG-based conversational question answering
 * </ul>
 *
 * <p>The service integrates multiple AI models and search backends:
 *
 * <ul>
 *   <li>Claude AI (Anthropic) for natural language understanding and response generation
 *   <li>OpenAI embeddings for vector similarity search
 *   <li>Apache Solr for both traditional and vector-based search with RRF support
 *   <li>PostgreSQL for conversation history persistence
 * </ul>
 *
 * @author Aditya Parikh
 * @since 1.0.0
 * @see SearchRepository
 * @see SearchController
 */
@Service
public class SearchService {

  private static final Logger log = LoggerFactory.getLogger(SearchService.class);

  /** A higher default topK improves recall for short, natural-language queries. */
  private static final int DEFAULT_SEMANTIC_TOP_K = 50;

  private static final int DEFAULT_HYBRID_TOP_K = 100;
  private static final double NO_SIMILARITY_THRESHOLD = 0.0;
  private static final String DEFAULT_CONVERSATION_ID = "default";
  private static final String FILTER_CONJUNCTION = " AND ";
  private static final String ID_FIELD = "id";
  private static final String CONTENT_FIELD = "content";
  private static final String EMBEDDING_METADATA_KEY = "embedding";
  private static final String YEAR_FIELD = "year";

  /** Prefix stripped from metadata keys, for consistency with the earlier API. */
  private static final String METADATA_PREFIX = "metadata_";

  private final Resource systemResource;
  private final SearchRepository searchRepository;
  private final ChatClient chatClient;
  private final ChatClient ragChatClient;
  private final VectorStoreFactory vectorStoreFactory;

  /**
   * Constructs a new SearchService with required dependencies.
   *
   * @param systemResource the system prompt template for traditional search query generation
   * @param searchRepository the repository for low-level Solr operations
   * @param chatClient the ChatClient configured for search query generation
   * @param ragChatClient the ChatClient configured with QuestionAnswerAdvisor for RAG
   * @param vectorStoreFactory the factory to obtain per-collection VectorStore instances
   */
  public SearchService(
      @Value("classpath:/prompts/system-message.st") Resource systemResource,
      SearchRepository searchRepository,
      @Qualifier("searchChatClient") ChatClient chatClient,
      @Qualifier("ragChatClient") ChatClient ragChatClient,
      VectorStoreFactory vectorStoreFactory) {
    this.systemResource = systemResource;
    this.searchRepository = searchRepository;
    this.chatClient = chatClient;
    this.ragChatClient = ragChatClient;
    this.vectorStoreFactory = vectorStoreFactory;
  }

  /**
   * Performs AI-enhanced traditional search on a Solr collection.
   *
   * <p>This method uses Claude AI to intelligently parse natural language queries and generate
   * optimized Solr search parameters including:
   *
   * <ul>
   *   <li>Query terms (q parameter)
   *   <li>Filter queries (fq parameter) for refinement
   *   <li>Sorting criteria
   *   <li>Field list for response
   *   <li>Faceting parameters for aggregation
   * </ul>
   *
   * <p>Example queries that Claude can understand:
   *
   * <ul>
   *   <li>"find all Java books published after 2020, sorted by date"
   *   <li>"search for spring framework tutorials with code examples"
   *   <li>"show me electronics under $500, group by brand"
   * </ul>
   *
   * @param collection the name of the Solr collection to search
   * @param freeTextQuery the natural language search query
   * @return a {@link SearchResponse} containing matched documents and facets
   * @throws IllegalArgumentException if collection or query is null or empty
   */
  public SearchResponse search(String collection, String freeTextQuery) {
    validateSearchInputs(collection, freeTextQuery);
    log.debug("Searching for collection: {}, query: {}", collection, freeTextQuery);

    QueryGenerationResponse queryGenerationResponse = generateQuery(collection, freeTextQuery);

    SearchRequest searchRequest =
        new SearchRequest(
            queryGenerationResponse.q(),
            queryGenerationResponse.fq(),
            queryGenerationResponse.sort(),
            queryGenerationResponse.fl(),
            new SearchRequest.Facet(
                queryGenerationResponse.facetFields(), queryGenerationResponse.facetQuery()));
    log.debug("Search request: {}", searchRequest);
    return searchRepository.search(collection, searchRequest);
  }

  /**
   * Performs semantic search using vector similarity with optional tuning parameters and field
   * selection.
   *
   * <p>This method leverages {@link SolrVectorStore#doSimilaritySearch} to avoid code duplication
   * and benefit from the observability features of {@link
   * org.springframework.ai.vectorstore.observation.AbstractObservationVectorStore}.
   *
   * @param collection the Solr collection to search
   * @param freeTextQuery the natural language search query
   * @param k optional topK results to return
   * @param minScore optional minimum similarity score threshold [0..1]
   * @param fieldsCsv optional comma-separated list of fields to include in the response (metadata
   *     keys or 'content')
   * @return search response with semantically similar documents
   */
  public SearchResponse semanticSearch(
      String collection,
      String freeTextQuery,
      @Nullable Integer k,
      @Nullable Double minScore,
      @Nullable String fieldsCsv) {
    validateSearchInputs(collection, freeTextQuery);
    log.debug(
        "Semantic search for collection: {}, query: {}, k: {}, minScore: {}, fields: {}",
        collection,
        freeTextQuery,
        k,
        minScore,
        fieldsCsv);

    // AI-generated filters are deliberately not applied here, to maximize semantic recall.
    org.springframework.ai.vectorstore.SearchRequest searchRequest =
        org.springframework.ai.vectorstore.SearchRequest.builder()
            .query(freeTextQuery)
            .topK(positiveOrDefault(k, DEFAULT_SEMANTIC_TOP_K))
            .similarityThreshold(minScore != null ? minScore : NO_SIMILARITY_THRESHOLD)
            .build();

    // Going through the VectorStore (not the repository) keeps its observability metrics.
    VectorStore vectorStore = vectorStoreFactory.forCollection(collection);
    List<Document> documents = vectorStore.similaritySearch(searchRequest);

    log.debug("Semantic search returned {} documents", documents.size());
    return toSearchResponse(documents, fieldsCsv);
  }

  /**
   * Performs hybrid search combining traditional keyword search with semantic vector search using
   * RRF.
   *
   * <p>This method leverages Reciprocal Rank Fusion (RRF) to combine results from two search
   * strategies:
   *
   * <ul>
   *   <li>Lexical search: Traditional keyword-based search using Solr's edismax query parser
   *   <li>Semantic search: Vector similarity search using KNN with embeddings
   * </ul>
   *
   * <p>RRF improves search quality by:
   *
   * <ol>
   *   <li>Running both searches independently
   *   <li>Ranking results from each search
   *   <li>Combining rankings using the formula: score = sum(1 / (k + rank))
   *   <li>Re-ranking final results by the combined score
   * </ol>
   *
   * @param collection the Solr collection to search
   * @param freeTextQuery the natural language search query
   * @param k optional topK results for vector search (defaults to 100)
   * @param minScore optional minimum cosine similarity [0..1], applied to the vector leg before
   *     fusion; it does not threshold keyword hits or the fused RRF score
   * @param fieldsCsv optional comma-separated list of fields to include in the response
   * @return search response with hybrid-ranked documents
   */
  public SearchResponse hybridSearch(
      String collection,
      String freeTextQuery,
      @Nullable Integer k,
      @Nullable Double minScore,
      @Nullable String fieldsCsv) {
    validateSearchInputs(collection, freeTextQuery);
    log.debug(
        "Hybrid search for collection: {}, query: {}, k: {}, minScore: {}, fields: {}",
        collection,
        freeTextQuery,
        k,
        minScore,
        fieldsCsv);

    QueryGenerationResponse queryGenerationResponse = generateQuery(collection, freeTextQuery);
    String filterExpression = buildFilterExpression(queryGenerationResponse.fq());

    return searchRepository.executeHybridRerankSearch(
        collection,
        queryGenerationResponse.q(),
        positiveOrDefault(k, DEFAULT_HYBRID_TOP_K),
        filterExpression,
        fieldsCsv,
        minScore);
  }

  /**
   * Performs conversational question-answering with RAG (Retrieval-Augmented Generation).
   *
   * <p>This method:
   *
   * <ol>
   *   <li>Uses QuestionAnswerAdvisor to automatically retrieve relevant context from VectorStore
   *   <li>Passes the question and retrieved context to Claude AI
   *   <li>Returns a natural language answer based on the indexed documents
   *   <li>Maintains conversation history for follow-up questions
   * </ol>
   *
   * <p>Unlike semantic search which returns document matches, this method returns a conversational
   * answer synthesized from the retrieved documents.
   *
   * @param askRequest the question and conversation context
   * @return conversational answer with source document IDs
   */
  public AskResponse ask(AskRequest askRequest) {
    log.debug("RAG question answering for: {}", askRequest.question());

    String conversationId =
        askRequest.conversationId() != null ? askRequest.conversationId() : DEFAULT_CONVERSATION_ID;

    // The full ChatClientResponse, not just content(): the retrieval advisor publishes the
    // documents it placed in the prompt into the response context, and those are the sources.
    ChatClientResponse chatClientResponse =
        ragChatClient
            .prompt()
            .user(askRequest.question())
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
            .call()
            .chatClientResponse();

    String answer = extractAnswer(chatClientResponse);
    List<String> sources = extractSources(chatClientResponse);

    log.debug("RAG answer generated from {} retrieved document(s)", sources.size());

    return new AskResponse(answer, conversationId, sources);
  }

  /**
   * Extracts the generated text from a chat client response.
   *
   * @return the answer, or an empty string if the model produced no result
   */
  private String extractAnswer(ChatClientResponse chatClientResponse) {
    ChatResponse chatResponse = chatClientResponse.chatResponse();
    if (chatResponse == null || chatResponse.getResult() == null) {
      log.warn("RAG chat response contained no result");
      return "";
    }
    String text = chatResponse.getResult().getOutput().getText();
    return text != null ? text : "";
  }

  /**
   * Extracts the IDs of the documents the retrieval advisor placed in the prompt context.
   *
   * <p>{@link RetrievalAugmentationAdvisor} publishes the retrieved documents under {@link
   * RetrievalAugmentationAdvisor#DOCUMENT_CONTEXT}. The list is absent when no retrieval ran, and
   * empty when retrieval found nothing.
   *
   * @return the source document IDs in retrieval order; never null
   */
  private List<String> extractSources(ChatClientResponse chatClientResponse) {
    Object retrieved =
        chatClientResponse.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
    if (!(retrieved instanceof List<?> documents)) {
      return List.of();
    }
    return documents.stream()
        .filter(Document.class::isInstance)
        .map(Document.class::cast)
        .map(Document::getId)
        .filter(Objects::nonNull)
        .toList();
  }

  /**
   * Validates search input parameters.
   *
   * @param collection the collection name
   * @param freeTextQuery the search query
   * @throws IllegalArgumentException if validation fails
   */
  private void validateSearchInputs(String collection, String freeTextQuery) {
    if (collection == null || collection.isBlank()) {
      throw new IllegalArgumentException("Collection name cannot be null or blank");
    }
    if (freeTextQuery == null || freeTextQuery.isBlank()) {
      throw new IllegalArgumentException("Query cannot be null or blank");
    }
  }

  private static int positiveOrDefault(@Nullable Integer value, int defaultValue) {
    return value != null && value > 0 ? value : defaultValue;
  }

  private QueryGenerationResponse generateQuery(String collection, String freeTextQuery) {
    return generateQueryWithAI(systemResource, buildQueryUserMessage(freeTextQuery, collection));
  }

  /**
   * Builds a user message for Claude AI by combining the query with field schema information.
   *
   * @param freeTextQuery the user's search query
   * @param collection the collection name to fetch field information from
   * @return formatted user message string
   */
  private String buildQueryUserMessage(String freeTextQuery, String collection) {
    List<FieldInfo> fields = searchRepository.getFieldsWithSchema(collection);
    return String.format(
        """
                The free text query is: %s
                The available fields with their types are: %s
                """,
        freeTextQuery, fields);
  }

  /**
   * Generates a structured query using Claude AI.
   *
   * @param systemPrompt the system prompt resource to use
   * @param userMessage the user message containing query and field information
   * @return the AI-generated query response
   */
  private QueryGenerationResponse generateQueryWithAI(Resource systemPrompt, String userMessage) {
    QueryGenerationResponse response =
        chatClient
            .prompt()
            .system(systemPrompt)
            .user(userMessage)
            // Constrain the reply to the response schema. Without this the model sometimes
            // answers, then "corrects" itself in the same text ("Wait, I must produce only
            // valid JSON..."), and parsing that prose-laden text fails with a 500.
            .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
            .call()
            .entity(QueryGenerationResponse.class);

    Objects.requireNonNull(response, "AI did not return a query generation response");
    log.debug("Query generation response: {}", response);
    return response;
  }

  /**
   * Builds a filter expression from a list of filter queries.
   *
   * @param filterQueries list of filter query strings
   * @return combined filter expression or null if no filters
   */
  private @Nullable String buildFilterExpression(@Nullable List<String> filterQueries) {
    if (filterQueries == null || filterQueries.isEmpty()) {
      return null;
    }
    return String.join(FILTER_CONJUNCTION, filterQueries);
  }

  /**
   * Converts Spring AI Documents to SearchResponse format.
   *
   * <p>This method transforms the VectorStore search results into the API response format, handling
   * metadata extraction and optional field filtering.
   *
   * @param documents the Spring AI Documents from VectorStore search
   * @param fieldsCsv optional comma-separated list of fields to include (null = all fields)
   * @return SearchResponse with converted documents
   */
  private SearchResponse toSearchResponse(List<Document> documents, @Nullable String fieldsCsv) {
    @Nullable List<String> requestedFields =
        fieldsCsv != null && !fieldsCsv.isBlank() ? List.of(fieldsCsv.split(",")) : null;
    List<Map<String, Object>> documentMaps =
        documents.stream().map(doc -> toDocumentMap(doc, requestedFields)).toList();
    return new SearchResponse(documentMaps, Map.of());
  }

  /**
   * Converts one document to its API form: id always, content and metadata only when requested
   * ({@code requestedFields} null means all fields).
   */
  private static Map<String, Object> toDocumentMap(
      Document doc, @Nullable List<String> requestedFields) {
    Map<String, Object> docMap = new HashMap<>();
    if (doc.getId() != null) {
      docMap.put(ID_FIELD, doc.getId());
    }
    if (doc.getText() != null && isRequested(requestedFields, CONTENT_FIELD)) {
      docMap.put(CONTENT_FIELD, doc.getText());
    }
    doc.getMetadata()
        .forEach(
            (key, value) -> {
              if (EMBEDDING_METADATA_KEY.equals(key)) {
                return; // internal, never part of the API response
              }
              String outputKey = stripMetadataPrefix(key);
              if (isRequested(requestedFields, outputKey) || isRequested(requestedFields, key)) {
                docMap.put(outputKey, normalizeValue(outputKey, value));
              }
            });
    return docMap;
  }

  private static boolean isRequested(@Nullable List<String> requestedFields, String field) {
    return requestedFields == null || requestedFields.contains(field);
  }

  private static String stripMetadataPrefix(String key) {
    return key.startsWith(METADATA_PREFIX) ? key.substring(METADATA_PREFIX.length()) : key;
  }

  /** Solr returns integer fields as Long; the API exposes the year as an Integer. */
  private static Object normalizeValue(String field, Object value) {
    return YEAR_FIELD.equals(field) && value instanceof Long year ? year.intValue() : value;
  }
}
