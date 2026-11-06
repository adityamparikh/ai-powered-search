package dev.aparikh.aipoweredsearch.search;

import static org.mockito.Mockito.mock;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class MockChatModelConfiguration {

  @Bean
  public ChatModel chatModel() {
    return mock(ChatModel.class);
  }

  @Bean
  public ChatClient chatClient() {
    return mock(ChatClient.class);
  }
}
