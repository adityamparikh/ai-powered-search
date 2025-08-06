package dev.aparikh.aipoweredsearch.search.controller;

import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import dev.aparikh.aipoweredsearch.search.service.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
class SearchController {

    private final SearchService searchService;

    SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/{collection}")
    public SearchResponse search(
            @PathVariable String collection,
            @RequestParam("query") String query) {
        return searchService.search(collection, query);
    }
}
