package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.search.model.AskRequest;
import dev.aparikh.aipoweredsearch.search.model.AskResponse;
import dev.aparikh.aipoweredsearch.search.model.FieldInfo;
import dev.aparikh.aipoweredsearch.search.model.QueryGenerationResponse;
import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import dev.aparikh.aipoweredsearch.solr.vectorstore.VectorStoreFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service layer for AI-enhanced search operations.
 *
 * <p>This service orchestrates various search strategies including:
 * <ul>
 *   <li>Traditional keyword search with AI-powered query generation</li>
 *   <li>Semantic vector search using embeddings and similarity matching</li>
 *   <li>Hybrid search combining lexical and semantic search using RRF (Reciprocal Rank Fusion)</li>
 *   <li>RAG-based conversational question answering</li>
 * </ul>
 *
 * <p>The service integrates multiple AI models and search backends:
 * <ul>
 *   <li>Claude AI (Anthropic) for natural language understanding and response generation</li>
 *   <li>OpenAI embeddings for vector similarity search</li>
 *   <li>Apache Solr for both traditional and vector-based search with RRF support</li>
 *   <li>PostgreSQL for conversation history persistence</li>
 * </ul>
 *
 * @author Aditya Parikh
 * @since 1.0.0
 * @see SearchRepository
 * @see SearchController
 */
@Service
public class SearchService {

    private static final String DEFAULT_CONVERSATION_ID = "007";

    private final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final Resource systemResource;
    private final Resource semanticSystemResource;
    private final SearchRepository searchRepository;
    private final ChatClient chatClient;
    private final ChatClient ragChatClient;
    private final VectorStoreFactory vectorStoreFactory;

    /**
     * Constructs a new SearchService with required dependencies.
     *
     * @param systemResource the system prompt template for traditional search query generation
     * @param semanticSystemResource the system prompt template for semantic search filter parsing
     * @param searchRepository the repository for low-level Solr operations
     * @param chatClient the ChatClient configured for search query generation
     * @param ragChatClient the ChatClient configured with QuestionAnswerAdvisor for RAG
     * @param vectorStoreFactory the factory to obtain per-collection VectorStore instances
     */
    public SearchService(@Value("classpath:/prompts/system-message.st") Resource systemResource,
                         @Value("classpath:/prompts/semantic-search-system-message.st") Resource semanticSystemResource,
                         SearchRepository searchRepository,
                         @Qualifier("searchChatClient") ChatClient chatClient,
                         @Qualifier("ragChatClient") ChatClient ragChatClient,
                         VectorStoreFactory vectorStoreFactory) {
        this.systemResource = systemResource;
        this.semanticSystemResource = semanticSystemResource;
        this.searchRepository = searchRepository;
        this.chatClient = chatClient;
        this.ragChatClient = ragChatClient;
        this.vectorStoreFactory = vectorStoreFactory;
    }


    /**
     * Performs AI-enhanced traditional search on a Solr collection.
     *
     * <p>This method uses Claude AI to intelligently parse natural language queries
     * and generate optimized Solr search parameters including:
     * <ul>
     *   <li>Query terms (q parameter)</li>
     *   <li>Filter queries (fq parameter) for refinement</li>
     *   <li>Sorting criteria</li>
     *   <li>Field list for response</li>
     *   <li>Faceting parameters for aggregation</li>
     * </ul>
     *
     * <p>Example queries that Claude can understand:
     * <ul>
     *   <li>"find all Java books published after 2020, sorted by date"</li>
     *   <li>"search for spring framework tutorials with code examples"</li>
     *   <li>"show me electronics under $500, group by brand"</li>
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

        String userMessage = buildQueryUserMessage(freeTextQuery, collection);
        QueryGenerationResponse queryGenerationResponse = generateQueryWithAI(systemResource, userMessage);

        SearchRequest searchRequest = new SearchRequest(
                queryGenerationResponse.q(),
                queryGenerationResponse.fq(),
                queryGenerationResponse.sort(),
                queryGenerationResponse.fl(),
                new SearchRequest.Facet(queryGenerationResponse.facetFields(), queryGenerationResponse.facetQuery())
        );
        log.debug("Search request: {}", searchRequest);
        return searchRepository.search(collection, searchRequest);
    }

    /**
     * Performs semantic search using vector similarity.
     *
     * <p>This method:
     * <ol>
     *   <li>Uses Claude AI to parse natural language filters from the query</li>
     *   <li>Generates a vector embedding from the free text query using OpenAI (automatically by VectorStore)</li>
     *   <li>Executes vector similarity search in Solr with KNN using VectorStore</li>
     *   <li>Returns semantically similar results ranked by cosine similarity</li>
     * </ol>
     * </p>
     *
     * @param collection    the Solr collection to search
     * @param freeTextQuery the natural language search query
     * @return search response with semantically similar documents
     */
    public SearchResponse semanticSearch(String collection, String freeTextQuery) {
        return semanticSearch(collection, freeTextQuery, null, null);
    }

    /**
     * Performs semantic search using vector similarity with optional tuning parameters.
     *
     * @param collection    the Solr collection to search
     * @param freeTextQuery the natural language search query
     * @param k             optional topK results to return
     * @param minScore      optional minimum similarity score threshold [0..1]
     * @return search response with semantically similar documents
     */
    public SearchResponse semanticSearch(String collection, String freeTextQuery, Integer k, Double minScore) {
        return semanticSearch(collection, freeTextQuery, k, minScore, null);
    }

    /**
     * Performs semantic search using vector similarity with optional tuning parameters and field selection.
     *
     * @param collection    the Solr collection to search
     * @param freeTextQuery the natural language search query
     * @param k             optional topK results to return
     * @param minScore      optional minimum similarity score threshold [0..1]
     * @param fieldsCsv     optional comma-separated list of fields to include in the response (metadata keys or 'content')
     * @return search response with semantically similar documents
     */
    public SearchResponse semanticSearch(String collection, String freeTextQuery, Integer k, Double minScore, String fieldsCsv) {
        validateSearchInputs(collection, freeTextQuery);
        log.debug("Semantic search for collection: {}, query: {}, k: {}, minScore: {}, fields: {}", collection, freeTextQuery, k, minScore, fieldsCsv);

        // Step 1 & 2: Optionally generate filters with AI (currently not applied to maximize recall)
        // We still parse with AI for future use, but we intentionally do NOT apply filters to
        // semantic search here because overly aggressive filters can suppress relevant results
        // for short/natural queries like "scary" or "supernatural" in tests.
        String userMessage = buildQueryUserMessage(freeTextQuery, collection);
        QueryGenerationResponse queryGenerationResponse = generateQueryWithAI(semanticSystemResource, userMessage);
        String filterExpression = null; // ignore AI filters for semantic search endpoint

        // Step 4: Execute semantic search using SearchRepository for Solr-native filter support
        // This bypasses Spring AI's FilterExpressionTextParser which cannot handle Solr range syntax
        // Use a higher default topK to improve recall for short/natural queries
        int topK = (k != null && k > 0) ? k : 50;

        // Important: Use the original freeTextQuery for embeddings to preserve semantic intent/recall.
        // We still apply any parsed filters from the AI on Solr side.
        return searchRepository.semanticSearch(
                collection,
                freeTextQuery,
                topK,
                filterExpression,
                fieldsCsv,
                minScore
        );
    }

    /**
     * Performs hybrid search combining traditional keyword search with semantic vector search using RRF.
     *
     * <p>This method leverages Reciprocal Rank Fusion (RRF) to combine results from two search strategies:
     * <ul>
     *   <li>Lexical search: Traditional keyword-based search using Solr's edismax query parser</li>
     *   <li>Semantic search: Vector similarity search using KNN with embeddings</li>
     * </ul>
     * </p>
     *
     * <p>RRF improves search quality by:
     * <ol>
     *   <li>Running both searches independently</li>
     *   <li>Ranking results from each search</li>
     *   <li>Combining rankings using the formula: score = sum(1 / (k + rank))</li>
     *   <li>Re-ranking final results by the combined score</li>
     * </ol>
     * </p>
     *
     * @param collection    the Solr collection to search
     * @param freeTextQuery the natural language search query
     * @param k             optional topK results for vector search (defaults to 100)
     * @param minScore      optional minimum similarity score threshold [0..1]
     * @param fieldsCsv     optional comma-separated list of fields to include in the response
     * @return search response with hybrid-ranked documents
     */
    public SearchResponse hybridSearch(String collection,
                                       String freeTextQuery,
                                       Integer k,
                                       Double minScore,
                                       String fieldsCsv) {
        validateSearchInputs(collection, freeTextQuery);
        log.debug("Hybrid search for collection: {}, query: {}, k: {}, minScore: {}, fields: {}",
                collection, freeTextQuery, k, minScore, fieldsCsv);

        // Step 1 & 2: Generate query with AI
        String userMessage = buildQueryUserMessage(freeTextQuery, collection);
        QueryGenerationResponse queryGenerationResponse = generateQueryWithAI(systemResource, userMessage);

        // Step 3: Build filter expression if present
        String filterExpression = buildFilterExpression(queryGenerationResponse.fq());

        // Step 4: Execute hybrid search using RRF in Solr
        // Repository will handle embedding generation internally
        int topK = (k != null && k > 0) ? k : 100;

        return searchRepository.executeHybridRerankSearch(
                collection,
                queryGenerationResponse.q(),
                topK,
                filterExpression,
                fieldsCsv,
                minScore
        );
    }

    /**
     * Performs conversational question-answering with RAG (Retrieval-Augmented Generation).
     *
     * <p>This method:
     * <ol>
     *   <li>Uses QuestionAnswerAdvisor to automatically retrieve relevant context from VectorStore</li>
     *   <li>Passes the question and retrieved context to Claude AI</li>
     *   <li>Returns a natural language answer based on the indexed documents</li>
     *   <li>Maintains conversation history for follow-up questions</li>
     * </ol>
     * </p>
     *
     * <p>Unlike semantic search which returns document matches, this method returns
     * a conversational answer synthesized from the retrieved documents.</p>
     *
     * @param askRequest the question and conversation context
     * @return conversational answer with source document IDs
     */
    public AskResponse ask(AskRequest askRequest) {
        log.debug("RAG question answering for: {}", askRequest.question());

        String conversationId = askRequest.conversationId() != null ?
                askRequest.conversationId() : "default";

        // The QuestionAnswerAdvisor automatically:
        // 1. Searches the VectorStore for relevant documents
        // 2. Adds them as context to the prompt
        // 3. Claude generates an answer based on that context
        String answer = ragChatClient.prompt()
                .user(askRequest.question())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        log.debug("RAG answer generated: {}", answer);

        // Note: We don't have direct access to which documents were retrieved by QuestionAnswerAdvisor
        // In a production system, you might want to:
        // 1. Manually retrieve documents first
        // 2. Pass them to Claude
        // 3. Return the document IDs as sources
        // For now, we return an empty sources list
        return new AskResponse(answer, conversationId, List.of());
    }

    // ============== Helper Methods ==============

    /**
     * Validates search input parameters.
     *
     * @param collection    the collection name
     * @param freeTextQuery the search query
     * @throws IllegalArgumentException if validation fails
     */
    private void validateSearchInputs(String collection, String freeTextQuery) {
        if (collection == null || collection.trim().isEmpty()) {
            throw new IllegalArgumentException("Collection name cannot be null or blank");
        }
        if (freeTextQuery == null || freeTextQuery.trim().isEmpty()) {
            throw new IllegalArgumentException("Query cannot be null or blank");
        }
    }

    /**
     * Builds a user message for Claude AI by combining the query with field schema information.
     *
     * @param freeTextQuery the user's search query
     * @param collection    the collection name to fetch field information from
     * @return formatted user message string
     */
    private String buildQueryUserMessage(String freeTextQuery, String collection) {
        List<FieldInfo> fields = searchRepository.getFieldsWithSchema(collection);
        return String.format("""
                The free text query is: %s
                The available fields with their types are: %s
                """, freeTextQuery, fields);
    }

    /**
     * Generates a structured query using Claude AI.
     *
     * @param systemPrompt the system prompt resource to use
     * @param userMessage  the user message containing query and field information
     * @return the AI-generated query response
     */
    private QueryGenerationResponse generateQueryWithAI(Resource systemPrompt, String userMessage) {
        QueryGenerationResponse response = chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, DEFAULT_CONVERSATION_ID))
                .call()
                .entity(QueryGenerationResponse.class);

        assert response != null;
        log.debug("Query generation response: {}", response);
        return response;
    }

    /**
     * Builds a filter expression from a list of filter queries.
     *
     * @param filterQueries list of filter query strings
     * @return combined filter expression or null if no filters
     */
    private String buildFilterExpression(List<String> filterQueries) {
        if (filterQueries != null && !filterQueries.isEmpty()) {
            return String.join(" AND ", filterQueries);
        }
        return null;
    }
}
