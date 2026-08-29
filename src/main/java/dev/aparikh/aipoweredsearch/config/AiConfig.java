package dev.aparikh.aipoweredsearch.config;

import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicCacheTtl;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.jspecify.annotations.Nullable;

/**
 * Spring AI configuration for multiple LLM providers.
 *
 * <p>This configuration is necessary when using multiple AI providers (Anthropic and OpenAI)
 * to prevent autoconfiguration conflicts. It explicitly defines beans with qualifiers
 * to disambiguate between different models.</p>
 *
 * <p>Configuration strategy based on:
 * <a href="https://www.danvega.dev/blog/spring-ai-multiple-llms">Spring AI Multiple LLMs</a></p>
 *
 * <h3>Bean Definitions:</h3>
 * <ul>
 *   <li>Anthropic ChatModel - Used for conversational AI and query generation</li>
 *   <li>OpenAI EmbeddingModel - Used for generating text embeddings for vector search</li>
 *   <li>ChatClient - Built using Anthropic ChatModel for existing search functionality</li>
 * </ul>
 */
@Configuration
public class AiConfig {

    /**
     * Anthropic chat model id used for query generation and RAG.
     *
     * <p>Spring AI 2.x removed the {@code AnthropicApi.ChatModel} enum when it moved to the
     * official Anthropic Java SDK, so the model is now identified by its string id.</p>
     */
    private static final String ANTHROPIC_CHAT_MODEL = "claude-sonnet-4-5";

    /**
     * Creates default AnthropicChatOptions with prompt caching enabled.
     *
     * <p>Prompt caching reduces costs by up to 90% and improves response times by up to 85%
     * for subsequent requests with identical prompts. This is particularly effective for
     * applications with stable system prompts or large tool definitions.</p>
     *
     * <p>Configuration properties:</p>
     * <ul>
     *   <li>spring.ai.anthropic.prompt-caching.enabled - Enable/disable caching (default: true)</li>
     *   <li>spring.ai.anthropic.prompt-caching.strategy - Cache strategy (default: SYSTEM_AND_TOOLS)</li>
     * </ul>
     *
     * <p>Available cache strategies:</p>
     * <ul>
     *   <li>NONE - Disables caching</li>
     *   <li>SYSTEM_ONLY - Caches system prompts (best for stable system prompts with &lt;20 tools)</li>
     *   <li>TOOLS_ONLY - Caches tool definitions (best for large tool sets with dynamic system prompts)</li>
     *   <li>SYSTEM_AND_TOOLS - Caches both independently (best for 20+ tools)</li>
     *   <li>CONVERSATION_HISTORY - Caches entire conversation history (best for multi-turn chats)</li>
     * </ul>
     *
     * <p>Spring AI 2.x changed {@code ChatClient.Builder#defaultOptions} to accept a
     * {@link org.springframework.ai.chat.prompt.ChatOptions.Builder} rather than a fully built
     * options instance, so this bean exposes the builder. Deferring the build lets Spring AI merge
     * per-request options over these defaults instead of replacing them wholesale.</p>
     *
     * @param cachingEnabled whether prompt caching is enabled
     * @param cacheStrategyStr the cache strategy to use
     * @return configured AnthropicChatOptions builder
     */
    @Bean
    @ConditionalOnProperty(name = "spring.ai.anthropic.prompt-caching.enabled", havingValue = "true", matchIfMissing = true)
    public AnthropicChatOptions.Builder anthropicChatOptionsWithCaching(
            @Value("${spring.ai.anthropic.prompt-caching.enabled:true}") boolean cachingEnabled,
            @Value("${spring.ai.anthropic.prompt-caching.strategy:SYSTEM_AND_TOOLS}") String cacheStrategyStr) {

        AnthropicCacheStrategy cacheStrategy = AnthropicCacheStrategy.valueOf(cacheStrategyStr);

        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder();
        builder.model(ANTHROPIC_CHAT_MODEL);
        builder.cacheOptions(AnthropicCacheOptions.builder()
                .strategy(cacheStrategy)
                .messageTypeTtl(MessageType.SYSTEM, AnthropicCacheTtl.ONE_HOUR)
                .build());
        return builder;
    }

    /**
     * Creates an OpenAI EmbeddingModel bean.
     *
     * <p>This bean is used for generating vector embeddings for semantic search.</p>
     *
     * <p>Spring AI 2.x replaced the hand-rolled {@code OpenAiApi} client with the official OpenAI
     * Java SDK, so the model is configured through {@link OpenAiEmbeddingOptions} instead. The
     * previous {@code restClientBuilder} workaround, which forced a JDK-HttpClient-backed
     * {@code RestClient} to avoid Jetty's strict HTTP protocol handling, is no longer needed: the
     * official SDK does not use Jetty at all.</p>
     *
     * <p>This project depends on the plain {@code spring-ai-openai} module rather than the OpenAI
     * starter, so no OpenAI autoconfiguration runs and the
     * {@code spring.ai.openai.embedding.options.*} properties are bound explicitly here.</p>
     *
     * @param apiKey the OpenAI API key from properties
     * @param model the embedding model id
     * @param dimensions the embedding vector dimensionality, which must match the Solr vector field
     * @return configured OpenAiEmbeddingModel instance
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public EmbeddingModel embeddingModel(
            @Value("${spring.ai.openai.api-key:${OPENAI_API_KEY:}}") String apiKey,
            @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}") String model,
            @Value("${spring.ai.openai.embedding.options.dimensions:1536}") Integer dimensions) {
        return new OpenAiEmbeddingModel(OpenAiEmbeddingOptions.builder()
                .apiKey(apiKey)
                .model(model)
                .dimensions(dimensions)
                .build());
    }

    /**
     * Creates a ChatClient bean for query generation (without RAG).
     *
     * <p>This bean is used by the SearchService for query generation and conversational search.
     * It must be explicitly defined because Spring AI cannot auto-configure when multiple
     * LLM providers are present.</p>
     *
     * <p>The ChatClient is configured with default advisors:
     * <ul>
     *   <li>MessageChatMemoryAdvisor - Maintains conversational context across requests</li>
     *   <li>SimpleLoggerAdvisor - Logs chat interactions for debugging</li>
     *   <li>PromptCacheMetricsAdvisor - Logs cache metrics when prompt caching is enabled</li>
     * </ul>
     * </p>
     *
     * @param chatModel the ChatModel (Anthropic) auto-configured by Spring AI
     * @param chatMemory the ChatMemory for maintaining conversation history
     * @param cachingEnabled whether prompt caching is enabled
     * @param chatOptions the chat options with caching configured (optional, may be null if caching disabled)
     * @return configured ChatClient instance
     */
    @Bean
    public ChatClient searchChatClient(ChatModel chatModel,
                                 ChatMemory chatMemory,
                                 @Value("${spring.ai.anthropic.prompt-caching.enabled:true}") boolean cachingEnabled,
                                 @Autowired(required = false) @Qualifier("anthropicChatOptionsWithCaching") AnthropicChatOptions.@Nullable Builder chatOptions) {
        ChatClient.Builder builder = ChatClient.builder(chatModel);

        // Set default options if caching is enabled
        if (cachingEnabled && chatOptions != null) {
            builder.defaultOptions(chatOptions);
        }

        return builder.defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        SimpleLoggerAdvisor.builder().build(),
                        PromptCacheMetricsAdvisor.builder()
                                .cachingEnabled(cachingEnabled)
                                .build()
                )
                .build();
    }

    /**
     * Creates a RAG-enabled ChatClient bean with QuestionAnswerAdvisor.
     *
     * <p>This bean is used for conversational question-answering with retrieval-augmented
     * generation (RAG). It automatically retrieves relevant context from the VectorStore
     * and includes it in the conversation.</p>
     *
     * <p>The ChatClient is configured with advisors:
     * <ul>
     *   <li>QuestionAnswerAdvisor - Retrieves context from VectorStore for RAG</li>
     *   <li>MessageChatMemoryAdvisor - Maintains conversational context across requests</li>
     *   <li>SimpleLoggerAdvisor - Logs chat interactions for debugging</li>
     *   <li>PromptCacheMetricsAdvisor - Logs cache metrics when prompt caching is enabled</li>
     * </ul>
     * </p>
     *
     * @param chatModel the ChatModel (Anthropic) auto-configured by Spring AI
     * @param chatMemory the ChatMemory for maintaining conversation history
     * @param vectorStore the VectorStore for retrieving relevant context
     * @param cachingEnabled whether prompt caching is enabled
     * @param chatOptions the chat options with caching configured (optional, may be null if caching disabled)
     * @return configured ChatClient instance with RAG capabilities
     */
    @Bean
    public ChatClient ragChatClient(ChatModel chatModel,
                                    ChatMemory chatMemory,
                                    VectorStore vectorStore,
                                    @Value("${spring.ai.anthropic.prompt-caching.enabled:true}") boolean cachingEnabled,
                                    @Autowired(required = false) @Qualifier("anthropicChatOptionsWithCaching") AnthropicChatOptions.@Nullable Builder chatOptions) {
        ChatClient.Builder builder = ChatClient.builder(chatModel);

        // Set default options if caching is enabled
        if (cachingEnabled && chatOptions != null) {
            builder.defaultOptions(chatOptions);
        }

        return builder.defaultAdvisors(
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .searchRequest(org.springframework.ai.vectorstore.SearchRequest.builder()
                                        .topK(5)
                                        // Lower threshold to ensure relevant context is retrieved reliably
                                        // across providers and embeddings in tests
                                        .similarityThreshold(0.3)
                                        .build())
                                .build(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        SimpleLoggerAdvisor.builder().build(),
                        PromptCacheMetricsAdvisor.builder()
                                .cachingEnabled(cachingEnabled)
                                .build()
                )
                .build();
    }
}
