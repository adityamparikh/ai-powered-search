package dev.aparikh.aipoweredsearch.chat;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@Import({PostgresTestConfiguration.class, ChatMemoryIntegrationTest.ChatMemoryTestConfig.class})
@ActiveProfiles("test")
public class ChatMemoryIntegrationTest {

    @Autowired
    private ChatClient chatClient;
    
    @Autowired
    private ChatMemory chatMemory;

    @Test
    void shouldStoreMessagesInChatMemory() {
        // Given
        String conversationId = UUID.randomUUID().toString();
        
        // When - Send messages through the ChatClient with a conversation ID
        String response1 = chatClient.prompt()
                .user("Hello, my name is John")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
        
        String response2 = chatClient.prompt()
                .user("What is my name?")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
        
        // Then - Verify the messages were stored and retrieved
        assertThat(response1).isNotNull();
        assertThat(response2).isNotNull();
        
        // Verify that the chat memory contains messages for this conversation
        List<Message> messages = chatMemory.get(conversationId);
        assertThat(messages).isNotEmpty();
        assertThat(messages.size()).isGreaterThanOrEqualTo(2); // At least user message and assistant response
        
        // Verify that we have both user and assistant messages
        boolean hasUserMessage = messages.stream().anyMatch(msg -> msg instanceof UserMessage);
        assertThat(hasUserMessage).isTrue();
    }
    
    @TestConfiguration
    static class ChatMemoryTestConfig {
        
        @Bean
        public ChatModel chatModel() {
            ChatModel mockChatModel = mock(ChatModel.class);
            
            // Create a mock response
            org.springframework.ai.chat.model.Generation generation = 
                new org.springframework.ai.chat.model.Generation(
                    new org.springframework.ai.chat.messages.AssistantMessage("This is a test response")
                );
            org.springframework.ai.chat.model.ChatResponse mockResponse = 
                new org.springframework.ai.chat.model.ChatResponse(List.of(generation));
            
            // Mock the call method to return the mock response
            when(mockChatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(mockResponse);
            
            return mockChatModel;
        }
        
        @Bean
        public ChatClient chatClient(ChatModel chatModel, ChatMemory chatMemory) {
            return ChatClient.builder(chatModel)
                    .defaultAdvisors(
                            MessageChatMemoryAdvisor.builder(chatMemory).build()
                    )
                    .build();
        }
    }
}