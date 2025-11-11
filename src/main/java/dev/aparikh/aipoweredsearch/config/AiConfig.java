package dev.aparikh.aipoweredsearch.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
     * Creates an OpenAI EmbeddingModel bean.
     *
     * <p>This bean is used for generating vector embeddings for semantic search.</p>
     *
     * <h3>Known Issue - Jetty Authentication Error:</h3>
     * <p>When using this with actual OpenAI API calls, you may encounter:</p>
     * <pre>
     * org.eclipse.jetty.client.HttpResponseException:
     *   HTTP protocol violation: Authentication challenge without WWW-Authenticate header
     * </pre>
     *
     * <p><b>Root Cause:</b> Jetty 12.x enforces strict HTTP protocol compliance. When OpenAI API
     * returns 401 status without proper WWW-Authenticate header, Jetty throws an exception.</p>
     *
     * <p><b>Solutions:</b></p>
     * <ul>
     *   <li><b>Use Valid API Key:</b> Ensure OPENAI_API_KEY environment variable contains a valid key</li>
     *   <li><b>For Tests:</b> Vector store tests automatically skip if OPENAI_API_KEY is not set</li>
     *   <li><b>Workaround:</b> The error typically occurs only with invalid/test API keys</li>
     * </ul>
     *
     * @param apiKey the OpenAI API key from properties
     * @return configured OpenAiEmbeddingModel instance
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public EmbeddingModel embeddingModel(@Value("${spring.ai.openai.api-key}") String apiKey) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(apiKey)
                .build();
        return new OpenAiEmbeddingModel(openAiApi);
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
     * </ul>
     * </p>
     *
     * @param chatModel the ChatModel (Anthropic) auto-configured by Spring AI
     * @param chatMemory the ChatMemory for maintaining conversation history
     * @return configured ChatClient instance
     */
    @Bean
    @Qualifier("searchChatClient")
    public ChatClient chatClient(ChatModel chatModel, ChatMemory chatMemory) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        SimpleLoggerAdvisor.builder().build()
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
     * </ul>
     * </p>
     *
     * @param chatModel the ChatModel (Anthropic) auto-configured by Spring AI
     * @param chatMemory the ChatMemory for maintaining conversation history
     * @param vectorStore the VectorStore for retrieving relevant context
     * @return configured ChatClient instance with RAG capabilities
     */
    @Bean
    @Qualifier("ragChatClient")
    public ChatClient ragChatClient(ChatModel chatModel, ChatMemory chatMemory, VectorStore vectorStore) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .searchRequest(org.springframework.ai.vectorstore.SearchRequest.builder()
                                        .topK(5)
                                        .similarityThreshold(0.7)
                                        .build())
                                .build(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        SimpleLoggerAdvisor.builder().build()
                )
                .build();
    }
}
