package dev.aparikh.aipoweredsearch.chat;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@Import({PostgresTestConfiguration.class, ChatMemoryIntegrationTest.TestConfig.class})
@ActiveProfiles("it")
class ChatMemoryIntegrationTest {

    @Autowired
    @Qualifier("searchChatClient")
    private ChatClient searchChatClient;

    @Autowired
    @Qualifier("ragChatClient")
    private ChatClient ragChatClient;

    @Autowired
    private ChatMemory chatMemory;


    @Test
    void searchChatClientShouldMaintainConversationMemory() {
        // Given
        String conversationId = UUID.randomUUID().toString();

        // When - Send messages through the ChatClient with a conversation ID
        String response1 = searchChatClient.prompt()
                .user("Hello, my name is Alice")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        String response2 = searchChatClient.prompt()
                .user("What is my name?")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        // Then - Verify the messages were stored and retrieved
        assertThat(response1).isNotNull();
        assertThat(response2).isNotNull();
        assertThat(response2).containsIgnoringCase("alice");

        // Verify that the chat memory contains messages for this conversation
        List<Message> messages = chatMemory.get(conversationId);
        assertThat(messages).isNotEmpty();
        assertThat(messages).hasSizeGreaterThanOrEqualTo(4); // 2 user messages and 2 assistant responses

        // Verify message types
        long userMessageCount = messages.stream().filter(msg -> msg instanceof UserMessage).count();
        long assistantMessageCount = messages.stream().filter(msg -> msg instanceof AssistantMessage).count();
        assertThat(userMessageCount).isGreaterThanOrEqualTo(2);
        assertThat(assistantMessageCount).isGreaterThanOrEqualTo(2);

        // Verify the first user message contains the name
        UserMessage firstUserMessage = messages.stream()
                .filter(msg -> msg instanceof UserMessage)
                .map(msg -> (UserMessage) msg)
                .findFirst()
                .orElse(null);
        assertThat(firstUserMessage).isNotNull();
        assertThat(firstUserMessage.getText()).containsIgnoringCase("alice");
    }

    @Test
    void ragChatClientShouldMaintainConversationMemory() {
        // Given
        String conversationId = UUID.randomUUID().toString();

        // When - Send messages through the RAG ChatClient with a conversation ID
        String response1 = ragChatClient.prompt()
                .user("Remember that my favorite color is blue")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        String response2 = ragChatClient.prompt()
                .user("What is my favorite color?")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        // Then - Verify the messages were stored and retrieved
        assertThat(response1).isNotNull();
        assertThat(response2).isNotNull();
        assertThat(response2).containsIgnoringCase("blue");

        // Verify that the chat memory contains messages for this conversation
        List<Message> messages = chatMemory.get(conversationId);
        assertThat(messages).isNotEmpty();
        assertThat(messages).hasSizeGreaterThanOrEqualTo(4); // 2 user messages and 2 assistant responses
    }

    @Test
    void differentConversationsShouldHaveSeparateMemories() {
        // Given
        String conversationId1 = UUID.randomUUID().toString();
        String conversationId2 = UUID.randomUUID().toString();

        // When - Send messages to different conversations
        searchChatClient.prompt()
                .user("My name is Bob")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId1))
                .call()
                .content();

        searchChatClient.prompt()
                .user("My name is Charlie")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId2))
                .call()
                .content();

        String response1 = searchChatClient.prompt()
                .user("What is my name?")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId1))
                .call()
                .content();

        String response2 = searchChatClient.prompt()
                .user("What is my name?")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId2))
                .call()
                .content();

        // Then - Each conversation should maintain its own context
        assertThat(response1).containsIgnoringCase("bob");
        assertThat(response2).containsIgnoringCase("charlie");

        // Verify separate memories
        List<Message> messages1 = chatMemory.get(conversationId1);
        List<Message> messages2 = chatMemory.get(conversationId2);

        assertThat(messages1).isNotEmpty();
        assertThat(messages2).isNotEmpty();

        // Verify conversation 1 has Bob, not Charlie
        String conversation1Content = messages1.stream()
                .map(Message::getText)
                .reduce("", (a, b) -> a + " " + b);
        assertThat(conversation1Content).containsIgnoringCase("bob");
        assertThat(conversation1Content).doesNotContainIgnoringCase("charlie");

        // Verify conversation 2 has Charlie, not Bob
        String conversation2Content = messages2.stream()
                .map(Message::getText)
                .reduce("", (a, b) -> a + " " + b);
        assertThat(conversation2Content).containsIgnoringCase("charlie");
        assertThat(conversation2Content).doesNotContainIgnoringCase("bob");
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        public VectorStore mockVectorStore() {
            VectorStore vectorStore = mock(VectorStore.class);
            // Return empty results for any similarity search
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
            when(vectorStore.similaritySearch(any(String.class))).thenReturn(List.of());
            return vectorStore;
        }

        @Bean
        @Primary
        public ChatModel mockChatModel() {
            ChatModel chatModel = mock(ChatModel.class);

            when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
                Prompt prompt = invocation.getArgument(0);
                String userMessage = prompt.getInstructions().stream()
                        .filter(msg -> msg instanceof UserMessage)
                        .map(msg -> ((UserMessage) msg).getText())
                        .reduce((first, second) -> second)
                        .orElse("");

                // Generate contextual responses based on conversation history
                String response;
                String fullPrompt = prompt.getInstructions().stream()
                        .map(Message::getText)
                        .reduce("", (a, b) -> a + " " + b).toLowerCase();

                if (userMessage.toLowerCase().contains("what is my name")) {
                    // Look for names in the conversation history
                    if (fullPrompt.contains("alice")) {
                        response = "Your name is Alice.";
                    } else if (fullPrompt.contains("bob")) {
                        response = "Your name is Bob.";
                    } else if (fullPrompt.contains("charlie")) {
                        response = "Your name is Charlie.";
                    } else {
                        response = "I don't know your name yet.";
                    }
                } else if (userMessage.toLowerCase().contains("what is my favorite color")) {
                    if (fullPrompt.contains("blue")) {
                        response = "Your favorite color is blue.";
                    } else {
                        response = "I don't know your favorite color.";
                    }
                } else if (userMessage.toLowerCase().contains("my name is")) {
                    response = "Nice to meet you!";
                } else if (userMessage.toLowerCase().contains("favorite color")) {
                    response = "I'll remember that.";
                } else {
                    response = "I understand.";
                }

                Generation generation = new Generation(new AssistantMessage(response));
                return new ChatResponse(List.of(generation));
            });

            return chatModel;
        }

        @Bean
        @Primary
        public EmbeddingModel mockEmbeddingModel() {
            EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

            float[] mockEmbeddingVector = new float[1536];
            for (int i = 0; i < 1536; i++) {
                mockEmbeddingVector[i] = 0.1f;
            }

            when(embeddingModel.embedForResponse(anyList())).thenAnswer(invocation -> {
                List<?> texts = invocation.getArgument(0);
                List<Embedding> embeddings = new ArrayList<>();
                for (int i = 0; i < texts.size(); i++) {
                    embeddings.add(new Embedding(mockEmbeddingVector, i));
                }
                return new EmbeddingResponse(embeddings);
            });

            when(embeddingModel.embed(any(org.springframework.ai.document.Document.class)))
                    .thenReturn(mockEmbeddingVector);

            when(embeddingModel.embed(any(String.class)))
                    .thenReturn(mockEmbeddingVector);

            when(embeddingModel.call(any(EmbeddingRequest.class))).thenAnswer(invocation -> {
                EmbeddingRequest request = invocation.getArgument(0);
                List<Embedding> embeddings = new ArrayList<>();
                for (int i = 0; i < request.getInstructions().size(); i++) {
                    embeddings.add(new Embedding(mockEmbeddingVector, i));
                }
                return new EmbeddingResponse(embeddings);
            });

            return embeddingModel;
        }
    }

}