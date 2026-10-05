package dev.aparikh.aipoweredsearch.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicChatOptionsTest {

    @Test
    void theCachingOptionsUseTheConfiguredChatModel() {
        // With prompt caching on (the default) these options are the chat clients' defaults, so a
        // hardcoded model here would override spring.ai.anthropic.chat.options.model.
        var options = new AiConfig().anthropicChatOptionsWithCaching(true, "SYSTEM_AND_TOOLS", "configured-model").build();

        assertThat(options.getModel()).isEqualTo("configured-model");
    }
}
