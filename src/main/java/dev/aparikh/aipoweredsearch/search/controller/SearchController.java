package dev.aparikh.aipoweredsearch.search.controller;

import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import dev.aparikh.aipoweredsearch.search.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "Search", description = "API for AI-enhanced search operations")
class SearchController {

    private final SearchService searchService;

    SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

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
}
