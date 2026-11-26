# Anthropic prompt caching

How the application uses Anthropic prompt caching, how to configure it, and what the
`PromptCacheMetricsAdvisor` logs. Routed from CLAUDE.md.

The application implements Anthropic's prompt caching feature to reduce costs by up to 90% and improve response times by
up to 85% for subsequent requests with identical prompts.

## Configuration

Prompt caching is configured via application properties and can be controlled with environment variables:

```properties
# Enable/disable prompt caching (default: true)
spring.ai.anthropic.prompt-caching.enabled=${ANTHROPIC_PROMPT_CACHING_ENABLED:true}
# Cache strategy (default: SYSTEM_AND_TOOLS)
spring.ai.anthropic.prompt-caching.strategy=${ANTHROPIC_PROMPT_CACHING_STRATEGY:SYSTEM_AND_TOOLS}
```

## Available Cache Strategies

| Strategy                 | Description                        | Best For                                                   |
|--------------------------|------------------------------------|------------------------------------------------------------|
| **NONE**                 | Disables caching                   | One-off requests with no repeated content                  |
| **SYSTEM_ONLY**          | Caches system prompts only         | Stable system prompts with <20 tools                       |
| **TOOLS_ONLY**           | Caches tool definitions only       | Large tool sets (5000+ tokens) with dynamic system prompts |
| **SYSTEM_AND_TOOLS**     | Caches both independently          | Applications with 20+ tools (default)                      |
| **CONVERSATION_HISTORY** | Caches entire conversation history | Multi-turn conversations with ChatClient memory            |

## Cache Metrics Logging

When caching is enabled, the `PromptCacheMetricsAdvisor` automatically logs cache performance metrics:

```log
[Prompt Caching] Cache HIT - Read: 2048 tokens, Regular input: 256 tokens, Output: 150 tokens
[Prompt Caching] Cost savings: ~80% (cache reads are 90% cheaper than regular input)
```

Or on cache miss (first request):

```log
[Prompt Caching] Cache MISS - Created: 2048 tokens, Regular input: 256 tokens, Output: 150 tokens
[Prompt Caching] Cache created. Subsequent requests with identical prompts will benefit from ~90% cost reduction
```

## Implementation Details

- **Location**: `src/main/java/dev/aparikh/aipoweredsearch/config/`
    - `AiConfig.java`: Configures AnthropicChatOptions with caching
    - `PromptCacheMetricsAdvisor.java`: Logs cache metrics as a CallAdvisor

- **How it works**:
    - Cache options are configured when creating ChatClient beans
    - The advisor intercepts all chat responses and extracts cache metrics from Anthropic API usage data
    - Metrics include: cache creation tokens, cache read tokens, regular input tokens, and output tokens
    - Cost savings are calculated based on Anthropic's pricing (cache reads are 90% cheaper)

- **Models and minimum cacheable prompt length**: see Anthropic's prompt caching documentation
  (https://docs.claude.com/en/docs/build-with-claude/prompt-caching). A prompt below the model's minimum
  is simply not cached; `PromptCacheMetricsAdvisor` then logs no cache activity.

## Disabling Prompt Caching

To disable prompt caching:

```bash
# Via environment variable
export ANTHROPIC_PROMPT_CACHING_ENABLED=false

# Or in application.properties
spring.ai.anthropic.prompt-caching.enabled=false
```

When disabled, no cache options are set and the advisor will not log cache metrics.
