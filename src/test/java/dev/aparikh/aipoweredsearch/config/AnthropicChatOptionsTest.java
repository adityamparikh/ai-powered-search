package dev.aparikh.aipoweredsearch.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatProperties;

class AnthropicChatOptionsTest {

  @Test
  void theCachingOptionsCarryEveryConfiguredChatProperty() {
    // With prompt caching on (the default) these options are the chat clients' defaults, so
    // they must start from spring.ai.anthropic.chat.* rather than from a blank builder.
    var properties = new AnthropicChatProperties();
    properties.setModel("configured-model");
    properties.setTemperature(0.2);

    var options =
        new AiConfig().anthropicChatOptionsWithCaching(properties, "SYSTEM_AND_TOOLS").build();

    assertThat(options.getModel()).isEqualTo("configured-model");
    assertThat(options.getTemperature()).isEqualTo(0.2);
    assertThat(options.getCacheOptions()).isNotNull();
  }
}
