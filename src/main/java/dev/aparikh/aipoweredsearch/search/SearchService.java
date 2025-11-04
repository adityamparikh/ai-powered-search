package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.search.model.FieldInfo;
import dev.aparikh.aipoweredsearch.search.model.QueryGenerationResponse;
import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.ai.vectorstore.SearchRequest.Builder;
import static org.springframework.ai.vectorstore.SearchRequest.builder;

@Service
public class SearchService {

    private final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final Resource systemResource;
    private final Resource semanticSystemResource;
    private final SearchRepository searchRepository;
    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public SearchService(@Value("classpath:/prompts/system-message.st") Resource systemResource,
                         @Value("classpath:/prompts/semantic-search-system-message.st") Resource semanticSystemResource,
                         SearchRepository searchRepository,
                         ChatClient chatClient,
                         VectorStore vectorStore) {
        this.systemResource = systemResource;
        this.semanticSystemResource = semanticSystemResource;
        this.searchRepository = searchRepository;
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
    }


    public SearchResponse search(String collection, String freeTextQuery) {
        log.debug("Searching for collection: {}, query: {}", collection, freeTextQuery);

        List<FieldInfo> fields = searchRepository.getFieldsWithSchema(collection);

        String userMessage = String.format("""
                The free text query is: %s
                The available fields with their types are: %s
                """, freeTextQuery, fields);

        String conversationId = "007";

        QueryGenerationResponse queryGenerationResponse = chatClient.prompt()
                .system(systemResource)
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .entity(QueryGenerationResponse.class);


        assert queryGenerationResponse != null;
        log.debug("Query generation response: {}", queryGenerationResponse);
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
        log.debug("Semantic search for collection: {}, query: {}", collection, freeTextQuery);

        // Step 1: Get field schema information
        List<FieldInfo> fields = searchRepository.getFieldsWithSchema(collection);

        // Step 2: Use Claude AI to parse filters and other search parameters
        String userMessage = String.format("""
                The free text query is: %s
                The available fields with their types are: %s
                """, freeTextQuery, fields);

        String conversationId = "007";

        QueryGenerationResponse queryGenerationResponse = chatClient.prompt()
                .system(semanticSystemResource)
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .entity(QueryGenerationResponse.class);

        assert queryGenerationResponse != null;
        log.debug("Query generation response for semantic search: {}", queryGenerationResponse);

        // Step 3: Build filter expression if present
        String filterExpression = null;
        if (queryGenerationResponse.fq() != null && !queryGenerationResponse.fq().isEmpty()) {
            filterExpression = String.join(" AND ", queryGenerationResponse.fq());
        }

        // Step 4: Execute semantic search using VectorStore
        // VectorStore will automatically generate embeddings from the query text
        Builder searchRequestBuilder = builder()
                        .query(freeTextQuery)
                        .topK(10);

        if (filterExpression != null) {
            searchRequestBuilder = searchRequestBuilder.filterExpression(filterExpression);
        }

        org.springframework.ai.vectorstore.SearchRequest searchRequest = searchRequestBuilder.build();
        List<Document> results = vectorStore.similaritySearch(searchRequest);

        log.debug("Semantic search returned {} results", results.size());

        // Step 5: Convert Spring AI Documents back to SearchResponse format
        List<Map<String, Object>> documents = results.stream()
                .map(doc -> {
                    Map<String, Object> docMap = new HashMap<>();
                    docMap.put("id", doc.getId());
                    docMap.put("content", doc.getText());
                    docMap.putAll(doc.getMetadata());
                    return docMap;
                })
                .toList();

        // Note: Faceting not currently supported in VectorStore similaritySearch
        // Could be enhanced by making a parallel Solr query for facets if needed
        return new SearchResponse(documents, Map.of());
    }
}
