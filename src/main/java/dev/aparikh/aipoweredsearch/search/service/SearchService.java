package dev.aparikh.aipoweredsearch.search.service;

import dev.aparikh.aipoweredsearch.search.model.FieldInfo;
import dev.aparikh.aipoweredsearch.search.model.QueryGenerationResponse;
import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import dev.aparikh.aipoweredsearch.search.repository.SearchRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private final Resource systemResource;
    private final SearchRepository searchRepository;
    private final ChatClient chatClient;

    public SearchService(@Value("classpath:/prompts/system-message.st") Resource systemResource,
                         SearchRepository searchRepository,
                         ChatModel chatModel,
                         ChatMemory chatMemory) {
        this.systemResource = systemResource;
        this.searchRepository = searchRepository;
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build() // chat-memory advisor
                )
                .build();
    }


    public SearchResponse search(String collection, String freeTextQuery) {
        List<FieldInfo> fields = searchRepository.getFieldsWithSchema(collection);

        // Format fields with type information for the AI
        String fieldsInfo = fields.stream()
                .map(FieldInfo::toSimpleString)
                .collect(Collectors.joining(", "));

        String userMessage = String.format("""
                The free text query is: %s
                The available fields with their types are: %s
                """, freeTextQuery, fieldsInfo);

        String conversationId = "007";

        QueryGenerationResponse queryGenerationResponse = chatClient.prompt()
                .system(systemResource)
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .entity(QueryGenerationResponse.class);


        assert queryGenerationResponse != null;
        SearchRequest searchRequest = new SearchRequest(
                queryGenerationResponse.q(),
                queryGenerationResponse.fq(),
                queryGenerationResponse.sort(),
                queryGenerationResponse.fl(),
                new SearchRequest.Facet(queryGenerationResponse.facetFields(), queryGenerationResponse.facetQuery())
        );

        return searchRepository.search(collection, searchRequest);
    }
}
