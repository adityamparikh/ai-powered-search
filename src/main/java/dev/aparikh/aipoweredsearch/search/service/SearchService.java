package dev.aparikh.aipoweredsearch.search.service;

import dev.aparikh.aipoweredsearch.search.model.QueryGenerationResponse;
import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import dev.aparikh.aipoweredsearch.search.repository.SearchRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class SearchService {

    private final SearchRepository searchRepository;
    private final ChatClient chatClient;

    public SearchService(SearchRepository searchRepository, ChatModel chatModel) {
        this.searchRepository = searchRepository;
        this.chatClient = ChatClient.create(chatModel);
    }

    public SearchResponse search(String collection, String freeTextQuery) {
        Set<String> fields = searchRepository.getActuallyUsedFields(collection);

        String systemMessage = """
                You are a search expert. You are given a free text query and a list of fields from a Solr schema.
                You need to convert the free text query into a structured search query.
                
                Respond with a JSON object with the following fields:
                - q: The main query string. This should be a valid Solr query.
                - fq: A list of filter queries. These should be valid Solr filter queries.
                - sort: The sort clause. This should be a valid Solr sort clause.
                - facet.fields: A list of fields to facet on.
                - facet.query: A list of facet queries.
                """;

        String userMessage = String.format("""
                The free text query is: %s
                The available fields are: %s
                """, freeTextQuery, fields);

        QueryGenerationResponse response = chatClient.prompt()
                .system(systemMessage)
                .user(userMessage)
                .call()
                .entity(QueryGenerationResponse.class);

        SearchRequest searchRequest = new SearchRequest(
                response.getQ(),
                response.getFq(),
                response.getSort(),
                new SearchRequest.Facet(response.getFacetFields(), response.getFacetQuery())
        );

        return searchRepository.search(collection, searchRequest);
    }
}
