package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.search.model.AskRequest;
import dev.aparikh.aipoweredsearch.search.model.AskResponse;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for AI-enhanced search operations.
 *
 * <p>This controller provides endpoints for various search functionalities including:
 * <ul>
 *   <li>Traditional keyword search with AI-powered query generation</li>
 *   <li>Semantic search using vector embeddings and similarity matching</li>
 *   <li>Conversational question-answering using RAG (Retrieval-Augmented Generation)</li>
 * </ul>
 *
 * <p>All search operations leverage AI models:
 * <ul>
 *   <li>Claude AI (Anthropic) for natural language understanding and query generation</li>
 *   <li>OpenAI embeddings for vector-based semantic search</li>
 *   <li>Solr for both traditional and vector-based search operations</li>
 * </ul>
 *
 * @author Aditya Parikh
 * @since 1.0.0
 * @see SearchService
 */
@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "Search", description = "API for AI-enhanced search operations")
class SearchController {

    private final SearchService searchService;

    /**
     * Constructs a new SearchController with the specified SearchService.
     *
     * @param searchService the service responsible for executing search operations
     */
    SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * Performs an AI-enhanced keyword search on the specified Solr collection.
     *
     * <p>This method uses Claude AI to transform the natural language query into
     * an optimized Solr query with appropriate filters, facets, and sorting parameters.
     * The AI understands search intent and generates structured queries accordingly.
     *
     * @param collection the name of the Solr collection to search
     * @param query the natural language search query (e.g., "find Java books published after 2020")
     * @return a {@link SearchResponse} containing matched documents and metadata
     * @throws IllegalArgumentException if collection or query is null or empty
     */
    @Operation(
        summary = "Search documents in a collection",
        description = "Performs an AI-enhanced keyword search on the specified collection using the provided query"
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Search completed successfully",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SearchResponse.class))
        ),
        @ApiResponse(responseCode = "400", description = "Invalid collection or query parameters"),
        @ApiResponse(responseCode = "404", description = "Collection not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping("/{collection}")
    public SearchResponse search(
            @Parameter(description = "Collection name to search in", required = true)
            @PathVariable String collection,
            @Parameter(description = "Search query string", required = true)
            @RequestParam("query") String query) {
        return searchService.search(collection, query);
    }

    /**
     * Performs semantic search using vector embeddings for similarity matching.
     *
     * <p>This method converts the query to a 1536-dimensional vector using OpenAI's
     * text-embedding-3-small model, then uses Solr's KNN search to find semantically
     * similar documents. Natural language filters are parsed by Claude AI.
     *
     * <p>Example queries:
     * <ul>
     *   <li>"comfortable running shoes under $100" - finds similar products with price filtering</li>
     *   <li>"machine learning frameworks for Java" - finds conceptually related documents</li>
     * </ul>
     *
     * @param collection the name of the Solr collection to search
     * @param query the natural language search query for semantic matching
     * @return a {@link SearchResponse} containing semantically similar documents with similarity scores
     * @throws IllegalArgumentException if collection or query is null or empty
     */
    @Operation(
        summary = "Semantic search using vector similarity",
        description = "Performs semantic search on the specified collection using vector embeddings. " +
                "The query is converted to a 1536-dimensional vector using OpenAI embeddings, " +
                "and Solr's KNN (K-Nearest Neighbors) search finds semantically similar documents. " +
                "Natural language filters are parsed by Claude AI and applied to refine results."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Semantic search completed successfully",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SearchResponse.class))
        ),
        @ApiResponse(responseCode = "400", description = "Invalid collection or query parameters"),
        @ApiResponse(responseCode = "404", description = "Collection not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping("/{collection}/semantic")
    public SearchResponse semanticSearch(
            @Parameter(description = "Collection name to search in", required = true, example = "products")
            @PathVariable String collection,
            @Parameter(description = "Natural language search query", required = true,
                    example = "machine learning frameworks for Java")
            @RequestParam("query") String query) {
        return searchService.semanticSearch(collection, query);
    }

    /**
     * Performs conversational question-answering using RAG (Retrieval-Augmented Generation).
     *
     * <p>This method leverages the QuestionAnswerAdvisor to automatically retrieve relevant
     * documents from the VectorStore based on the question, then uses Claude AI to generate
     * a contextual answer. Conversation history is maintained using the provided conversationId,
     * allowing for follow-up questions with context.
     *
     * <p>The RAG process:
     * <ol>
     *   <li>Question is converted to embeddings using OpenAI</li>
     *   <li>Vector similarity search retrieves relevant documents from Solr</li>
     *   <li>Retrieved documents are added to the prompt context</li>
     *   <li>Claude AI generates an answer based on the retrieved context</li>
     * </ol>
     *
     * @param askRequest the request containing the question and optional conversation ID
     * @return an {@link AskResponse} with the generated answer and conversation metadata
     * @throws IllegalArgumentException if the question is null or empty
     */
    @Operation(
        summary = "Ask a question with RAG (Retrieval-Augmented Generation)",
        description = "Performs conversational question-answering using RAG. " +
                "The QuestionAnswerAdvisor automatically retrieves relevant documents from the VectorStore, " +
                "and Claude AI generates a natural language answer based on the retrieved context. " +
                "Maintains conversation history for follow-up questions using the conversationId."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Answer generated successfully",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AskResponse.class))
        ),
        @ApiResponse(responseCode = "400", description = "Invalid request"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PostMapping("/ask")
    public AskResponse ask(
            @Parameter(description = "Question request with optional conversation ID", required = true)
            @RequestBody AskRequest askRequest) {
        return searchService.ask(askRequest);
    }
}
