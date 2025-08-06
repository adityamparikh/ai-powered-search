package dev.aparikh.aipoweredsearch.search;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.Mockito.mock;

@TestConfiguration
public class MockChatModelConfiguration {

    @Bean
    @Primary
    public ChatModel chatModel() {
        return mock(ChatModel.class);
    }
    
    @Bean
    @Primary
    public ChatClient chatClient() {
        return mock(ChatClient.class);
    }
}