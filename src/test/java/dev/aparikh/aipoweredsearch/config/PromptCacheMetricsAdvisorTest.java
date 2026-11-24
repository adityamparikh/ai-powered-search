package dev.aparikh.aipoweredsearch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Unit tests for {@link PromptCacheMetricsAdvisor}, with no API key: the Anthropic usage block is
 * mocked. {@code PromptCacheMetricsAdvisorIT} checks the same logging against the real API.
 */
@ExtendWith(OutputCaptureExtension.class)
class PromptCacheMetricsAdvisorTest {

  private final AdvisorChain chain = mock(AdvisorChain.class);
  private final PromptCacheMetricsAdvisor advisor =
      PromptCacheMetricsAdvisor.builder().cachingEnabled(true).build();

  @Test
  void logsACacheHitWithItsSavings(CapturedOutput output) {
    advisor.after(response(anthropicUsage(null, 1000L, 100, 50)), chain);

    assertThat(output)
        .contains("[Prompt Caching] Cache HIT - Read: 1000 tokens, Regular input: 100 tokens")
        // (1100 - (100 + 100)) / 1100 = 81.8%
        .contains("[Prompt Caching] Cost savings: ~81.8%");
  }

  @Test
  void logsACacheMissThatCreatedTheCache(CapturedOutput output) {
    advisor.after(response(anthropicUsage(2048L, 0L, 256, 150)), chain);

    assertThat(output)
        .contains("[Prompt Caching] Cache MISS - Created: 2048 tokens, Regular input: 256 tokens")
        .contains("Cache created.")
        .doesNotContain("Cache HIT");
  }

  @Test
  void staysQuietWithoutCacheActivity(CapturedOutput output) {
    advisor.after(response(anthropicUsage(null, null, 300, 20)), chain);

    assertThat(output).doesNotContain("Cache HIT").doesNotContain("Cache MISS");
  }

  @Test
  void ignoresUsageThatIsNotFromAnthropic(CapturedOutput output) {
    ChatClientResponse response = response(new DefaultUsage(10, 5, 15, "not anthropic"));

    assertThat(advisor.after(response, chain)).isSameAs(response);
    assertThat(output).doesNotContain("Cache HIT").doesNotContain("Cache MISS");
  }

  @Test
  void toleratesResponsesWithoutUsageOrChatResponse() {
    ChatClientResponse noNativeUsage = response(new DefaultUsage(10, 5));
    ChatClientResponse noChatResponse = ChatClientResponse.builder().build();

    assertThat(advisor.after(noNativeUsage, chain)).isSameAs(noNativeUsage);
    assertThat(advisor.after(noChatResponse, chain)).isSameAs(noChatResponse);
  }

  @Test
  void doesNothingWhenCachingIsDisabled(CapturedOutput output) {
    PromptCacheMetricsAdvisor disabled =
        PromptCacheMetricsAdvisor.builder().cachingEnabled(false).build();
    ChatClientResponse response = response(anthropicUsage(null, 1000L, 100, 50));

    assertThat(disabled.after(response, chain)).isSameAs(response);
    assertThat(output).doesNotContain("Cache HIT");
  }

  @Test
  void passesRequestsThroughUnchanged() {
    ChatClientRequest request = ChatClientRequest.builder().prompt(new Prompt("hi")).build();

    assertThat(advisor.before(request, chain)).isSameAs(request);
    assertThat(
            PromptCacheMetricsAdvisor.builder()
                .cachingEnabled(false)
                .build()
                .before(request, chain))
        .isSameAs(request);
    assertThat(advisor.getName()).isEqualTo("PromptCacheMetricsAdvisor");
    assertThat(advisor.getOrder()).isZero();
  }

  private static com.anthropic.models.messages.Usage anthropicUsage(
      Long cacheCreation, Long cacheRead, long input, long output) {
    com.anthropic.models.messages.Usage usage = mock(com.anthropic.models.messages.Usage.class);
    when(usage.cacheCreationInputTokens()).thenReturn(Optional.ofNullable(cacheCreation));
    when(usage.cacheReadInputTokens()).thenReturn(Optional.ofNullable(cacheRead));
    when(usage.inputTokens()).thenReturn(input);
    when(usage.outputTokens()).thenReturn(output);
    return usage;
  }

  private static ChatClientResponse response(com.anthropic.models.messages.Usage usage) {
    return response(new DefaultUsage(0, 0, 0, usage));
  }

  private static ChatClientResponse response(DefaultUsage usage) {
    ChatResponse chatResponse =
        new ChatResponse(
            List.of(new Generation(new AssistantMessage("ok"))),
            ChatResponseMetadata.builder().usage(usage).build());
    return ChatClientResponse.builder().chatResponse(chatResponse).build();
  }
}
