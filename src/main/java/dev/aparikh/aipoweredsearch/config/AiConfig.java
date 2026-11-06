package dev.aparikh.aipoweredsearch.config;

import dev.aparikh.aipoweredsearch.search.HybridDocumentRetriever;
import dev.aparikh.aipoweredsearch.search.RerankingDocumentPostProcessor;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicCacheTtl;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatProperties;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI configuration for multiple LLM providers.
 *
 * <p>This configuration is necessary when using multiple AI providers (Anthropic and OpenAI) to
 * prevent autoconfiguration conflicts. It explicitly defines beans with qualifiers to disambiguate
 * between different models.
 *
 * <p>Configuration strategy based on: <a
 * href="https://www.danvega.dev/blog/spring-ai-multiple-llms">Spring AI Multiple LLMs</a>
 *
 * <h3>Bean Definitions:</h3>
 *
 * <ul>
 *   <li>Anthropic ChatModel - Used for conversational AI and query generation
 *   <li>OpenAI EmbeddingModel - Used for generating text embeddings for vector search
 *   <li>ChatClient - Built using Anthropic ChatModel for existing search functionality
 * </ul>
 */
@Configuration
public class AiConfig {

  private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

  /** Bean name of a decisions provider's passage filter, see {@link TypeSafeDecisionsConfig}. */
  public static final String DECISIONS_FILTER = "decisionsFilter";

  /** Bean name of a decisions provider's reranker, see {@link TypeSafeDecisionsConfig}. */
  public static final String DECISIONS_RERANKER = "decisionsReranker";

  /** Query-context key under which a follow-up's standalone rewrite reaches the post-processors. */
  static final String STANDALONE_QUERY = "rag.standalone";

  /**
   * Creates default AnthropicChatOptions with prompt caching enabled.
   *
   * <p>Prompt caching reduces costs by up to 90% and improves response times by up to 85% for
   * subsequent requests with identical prompts. This is particularly effective for applications
   * with stable system prompts or large tool definitions.
   *
   * <p>Configuration properties:
   *
   * <ul>
   *   <li>spring.ai.anthropic.prompt-caching.enabled - Enable/disable caching (default: true)
   *   <li>spring.ai.anthropic.prompt-caching.strategy - Cache strategy (default: SYSTEM_AND_TOOLS)
   * </ul>
   *
   * <p>Available cache strategies:
   *
   * <ul>
   *   <li>NONE - Disables caching
   *   <li>SYSTEM_ONLY - Caches system prompts (best for stable system prompts with &lt;20 tools)
   *   <li>TOOLS_ONLY - Caches tool definitions (best for large tool sets with dynamic system
   *       prompts)
   *   <li>SYSTEM_AND_TOOLS - Caches both independently (best for 20+ tools)
   *   <li>CONVERSATION_HISTORY - Caches entire conversation history (best for multi-turn chats)
   * </ul>
   *
   * <p>Spring AI 2.x changed {@code ChatClient.Builder#defaultOptions} to accept a {@link
   * org.springframework.ai.chat.prompt.ChatOptions.Builder} rather than a fully built options
   * instance, so this bean exposes the builder. Deferring the build lets Spring AI merge
   * per-request options over these defaults instead of replacing them wholesale.
   *
   * @param chatProperties Spring AI's bound {@code spring.ai.anthropic.chat.*} properties. These
   *     options are the chat clients' defaults, so they start from every configured chat property
   *     (model, max tokens, temperature, ...) rather than a fixed model.
   * @param cacheStrategyStr the cache strategy to use
   * @return configured AnthropicChatOptions builder
   */
  @Bean
  @ConditionalOnProperty(
      name = "spring.ai.anthropic.prompt-caching.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public AnthropicChatOptions.Builder anthropicChatOptionsWithCaching(
      AnthropicChatProperties chatProperties,
      @Value("${spring.ai.anthropic.prompt-caching.strategy:SYSTEM_AND_TOOLS}")
          String cacheStrategyStr) {

    AnthropicCacheStrategy cacheStrategy = AnthropicCacheStrategy.valueOf(cacheStrategyStr);

    AnthropicChatOptions.Builder builder = chatProperties.toOptions().mutate();
    builder.cacheOptions(
        AnthropicCacheOptions.builder()
            .strategy(cacheStrategy)
            .messageTypeTtl(MessageType.SYSTEM, AnthropicCacheTtl.ONE_HOUR)
            .build());
    return builder;
  }

  /**
   * Creates an OpenAI EmbeddingModel bean.
   *
   * <p>This bean is used for generating vector embeddings for semantic search.
   *
   * <p>Spring AI 2.x replaced the hand-rolled {@code OpenAiApi} client with the official OpenAI
   * Java SDK, so the model is configured through {@link OpenAiEmbeddingOptions} instead. The
   * previous {@code restClientBuilder} workaround, which forced a JDK-HttpClient-backed {@code
   * RestClient} to avoid Jetty's strict HTTP protocol handling, is no longer needed: the official
   * SDK does not use Jetty at all.
   *
   * <p>This project depends on the plain {@code spring-ai-openai} module rather than the OpenAI
   * starter, so no OpenAI autoconfiguration runs and the {@code
   * spring.ai.openai.embedding.options.*} properties are bound explicitly here.
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
    return new OpenAiEmbeddingModel(
        OpenAiEmbeddingOptions.builder()
            .apiKey(apiKey)
            .model(model)
            .dimensions(dimensions)
            .build());
  }

  /**
   * Creates a ChatClient bean for query generation (without RAG).
   *
   * <p>This bean is used by the SearchService to turn a free-text query into Solr query parameters.
   * It must be explicitly defined because Spring AI cannot auto-configure when multiple LLM
   * providers are present.
   *
   * <p><strong>Stateless by design: no chat memory.</strong> Each search is independent. A memory
   * advisor here would need a conversation ID, and the search endpoints have none to give it: it
   * used to be a hardcoded {@code "007"} shared by every caller, so one user's queries (and
   * Claude's earlier JSON answers) leaked into everyone else's query generation, and the growing
   * history defeated prompt caching. Conversational context belongs to {@code /ask}, whose {@code
   * ragChatClient} keys memory by the request's conversation ID.
   *
   * <p>The ChatClient is configured with default advisors:
   *
   * <ul>
   *   <li>SimpleLoggerAdvisor - Logs chat interactions for debugging
   *   <li>PromptCacheMetricsAdvisor - Logs cache metrics when prompt caching is enabled
   * </ul>
   *
   * @param chatModel the ChatModel (Anthropic) auto-configured by Spring AI
   * @param cachingEnabled whether prompt caching is enabled
   * @param chatOptions the chat options with caching configured (optional, may be null if caching
   *     disabled)
   * @return configured ChatClient instance
   */
  @Bean
  public ChatClient searchChatClient(
      ChatModel chatModel,
      @Value("${spring.ai.anthropic.prompt-caching.enabled:true}") boolean cachingEnabled,
      @Autowired(required = false) @Qualifier("anthropicChatOptionsWithCaching")
          AnthropicChatOptions.@Nullable Builder chatOptions) {
    ChatClient.Builder builder = ChatClient.builder(chatModel);

    // Set default options if caching is enabled
    if (cachingEnabled && chatOptions != null) {
      builder.defaultOptions(chatOptions);
    }

    return builder
        .defaultAdvisors(
            SimpleLoggerAdvisor.builder().build(),
            PromptCacheMetricsAdvisor.builder().cachingEnabled(cachingEnabled).build())
        .build();
  }

  /**
   * Creates a RAG-enabled ChatClient bean backed by hybrid retrieval.
   *
   * <p>This bean is used for conversational question-answering with retrieval-augmented generation
   * (RAG). It automatically retrieves relevant context from the VectorStore and includes it in the
   * conversation.
   *
   * <p>Retrieval goes through {@link HybridDocumentRetriever}, so distinctive terminology in the
   * question is matched lexically by BM25 while its meaning is matched by vector similarity, with
   * the two rankings fused by Reciprocal Rank Fusion.
   *
   * <p>The ChatClient is configured with advisors:
   *
   * <ul>
   *   <li>RetrievalAugmentationAdvisor - Retrieves context via hybrid search (keyword + vector,
   *       fused with RRF) rather than vector similarity alone
   *   <li>MessageChatMemoryAdvisor - Maintains conversational context across requests
   *   <li>SimpleLoggerAdvisor - Logs chat interactions for debugging
   *   <li>PromptCacheMetricsAdvisor - Logs cache metrics when prompt caching is enabled
   * </ul>
   *
   * @param chatModel the ChatModel (Anthropic) auto-configured by Spring AI
   * @param chatMemory the ChatMemory for maintaining conversation history
   * @param hybridDocumentRetriever retrieves RAG context using RRF-fused hybrid search
   * @param cachingEnabled whether prompt caching is enabled
   * @param chatOptions the chat options with caching configured (optional, may be null if caching
   *     disabled)
   * @param reranker the reranking post-processor, absent when {@code
   *     search.rag.rerank.enabled=false}
   * @param queryTransformer rewrites each question into a standalone query using the conversation
   * @param decisionsFilter a decisions provider's passage filter, present with {@code
   *     search.rag.decisions.enabled=true}
   * @param decisionsReranker a decisions provider's reranker, used instead of {@code reranker} when
   *     present
   * @param decisionsEnabled {@code search.rag.decisions.enabled}
   * @param rerankProvider {@code search.rag.rerank.provider}: {@code claude} (default) or {@code
   *     decisions}
   * @return configured ChatClient instance with RAG capabilities
   */
  @Bean
  public ChatClient ragChatClient(
      ChatModel chatModel,
      ChatMemory chatMemory,
      HybridDocumentRetriever hybridDocumentRetriever,
      @Value("${spring.ai.anthropic.prompt-caching.enabled:true}") boolean cachingEnabled,
      @Autowired(required = false) @Qualifier("anthropicChatOptionsWithCaching")
          AnthropicChatOptions.@Nullable Builder chatOptions,
      @Autowired(required = false) @Nullable RerankingDocumentPostProcessor reranker,
      QueryTransformer queryTransformer,
      @Autowired(required = false) @Qualifier(DECISIONS_FILTER)
          @Nullable DocumentPostProcessor decisionsFilter,
      @Autowired(required = false) @Qualifier(DECISIONS_RERANKER)
          @Nullable DocumentPostProcessor decisionsReranker,
      @Value("${search.rag.decisions.enabled:false}") boolean decisionsEnabled,
      @Value("${search.rag.rerank.provider:claude}") String rerankProvider) {
    ChatClient.Builder builder = ChatClient.builder(chatModel);

    // Set default options if caching is enabled
    if (cachingEnabled && chatOptions != null) {
      builder.defaultOptions(chatOptions);
    }

    RetrievalAugmentationAdvisor.Builder ragAdvisor =
        RetrievalAugmentationAdvisor.builder()
            // Conversation-aware retrieval: "Anything cheaper by the same author?"
            // is searched as a standalone query with the author filled in.
            .queryTransformers(queryTransformer)
            .documentRetriever(hybridDocumentRetriever)
            // RetrievalAugmentationAdvisor refuses to answer when retrieval
            // returns nothing; QuestionAnswerAdvisor did not. Follow-up turns
            // in an ongoing conversation are often answerable from chat memory
            // alone, so preserve the previous behaviour.
            .queryAugmenter(ContextualQueryAugmenter.builder().allowEmptyContext(true).build())
            // Pass-through joiner. The default ConcatenationDocumentJoiner
            // re-sorts documents by their individual score, which would undo
            // the RRF order Solr returned. Hybrid documents carry no score
            // (Solr's per-document score in combined mode is a raw per-leg
            // score, not the fused one), so list order is the only ranking.
            .documentJoiner(
                documentsForQuery ->
                    documentsForQuery.values().stream()
                        .flatMap(List::stream)
                        .flatMap(List::stream)
                        .toList());

    // Post-processing decides which candidates actually reach the prompt. The optional decisions
    // filter screens them first, so the reranker only reads passages that survived screening.
    // Each stage judges a follow-up's standalone rewrite rather than the raw follow-up.
    // Reranking is absent when search.rag.rerank.enabled=false.
    List<DocumentPostProcessor> postProcessors = new ArrayList<>(2);
    if (decisionsFilter != null) {
      postProcessors.add(judgedAgainstTheRewrite(decisionsFilter));
    } else if (decisionsEnabled) {
      // Screening was asked for; serving unscreened passages must not go unnoticed.
      log.warn(
          "search.rag.decisions.enabled=true but no decisions provider is configured, so retrieved "
              + "passages are NOT screened. The 'typesafe' provider needs spring.ai.typesafe.api-key "
              + "(TYPESAFE_API_KEY; any placeholder for a local model such as Laya)");
    }
    // Reranking is Claude's unless search.rag.rerank.provider=decisions selected a decisions
    // reranker.
    DocumentPostProcessor rerank = decisionsReranker != null ? decisionsReranker : reranker;
    if (decisionsReranker == null && !"claude".equalsIgnoreCase(rerankProvider.strip())) {
      log.warn(
          "search.rag.rerank.provider={} has no decisions reranker (use 'decisions' with a configured "
              + "decisions provider); reranking with Claude",
          rerankProvider);
    }
    if (rerank != null) {
      postProcessors.add(judgedAgainstTheRewrite(rerank));
    }
    log.info(
        "RAG post-processors: decisions filter {}, reranker {}",
        decisionsFilter == null ? "off" : decisionsFilter.getClass().getSimpleName(),
        rerank == null ? "off" : rerank.getClass().getSimpleName());
    if (!postProcessors.isEmpty()) {
      ragAdvisor.documentPostProcessors(postProcessors);
    }

    return builder
        .defaultAdvisors(
            ragAdvisor.build(),
            MessageChatMemoryAdvisor.builder(chatMemory).build(),
            SimpleLoggerAdvisor.builder().build(),
            PromptCacheMetricsAdvisor.builder().cachingEnabled(cachingEnabled).build())
        .build();
  }

  /**
   * Creates the LLM-based reranker that trims retrieved context down to the most relevant
   * documents.
   *
   * <p>Disable with {@code search.rag.rerank.enabled=false} to avoid the extra model call. If you
   * do, consider lowering {@code search.rag.hybrid.top-k} as well — retrieval over-fetches on the
   * assumption that reranking will discard the surplus, and without a reranker every retrieved
   * chunk goes straight into the prompt.
   *
   * @param chatModel the ChatModel used to judge relevance
   * @param topK how many documents survive reranking
   * @return the reranking post-processor
   */
  @Bean
  @ConditionalOnProperty(
      name = "search.rag.rerank.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public RerankingDocumentPostProcessor rerankingDocumentPostProcessor(
      ChatModel chatModel, @Value("${search.rag.rerank.top-k:5}") int topK) {
    return new RerankingDocumentPostProcessor(ChatClient.builder(chatModel).build(), topK);
  }

  /**
   * Rewrites a follow-up question, together with the conversation so far, into a standalone query
   * before retrieval. Chat memory lets the model answer a follow-up, but without this the retriever
   * would search for the follow-up's literal words.
   *
   * <p>Rewriting a query is a small task, so it runs on a smaller, cheaper model than the answer:
   * {@code search.rag.query-rewrite.model}, default {@code claude-haiku-4-5}.
   *
   * @param chatModel the ChatModel the rewrite runs on
   * @param model the model id for the rewrite
   * @return the query transformer
   */
  @Bean
  public QueryTransformer queryTransformer(
      ChatModel chatModel,
      @Value("${search.rag.query-rewrite.model:claude-haiku-4-5}") String model) {
    QueryTransformer compression =
        CompressionQueryTransformer.builder()
            .chatClientBuilder(
                ChatClient.builder(chatModel)
                    .defaultOptions(AnthropicChatOptions.builder().model(model)))
            .build();
    return query -> {
      // A first question has no conversation to fold in, so it is searched as asked. Neither is
      // the shared "default" conversation (requests without a conversationId), whose history
      // mixes unrelated callers.
      if (!hasEarlierTurns(query.history())
          || "default".equals(query.context().get(ChatMemory.CONVERSATION_ID))) {
        return query;
      }
      Query rewritten;
      try {
        rewritten = compression.transform(query);
      } catch (RuntimeException e) {
        // The rewrite improves retrieval; it must not fail the request.
        log.warn("Query rewrite failed; retrieving with the question as asked: {}", e.toString());
        return query;
      }
      // The advisor hands post-processors the original query: leave the rewrite in its
      // context (the advisor's own mutable map) so the reranker judges against it.
      query.context().put(STANDALONE_QUERY, rewritten.text());
      return rewritten;
    };
  }

  /**
   * Whether the conversation has a turn before the current question. {@code Query.history()} is the
   * whole prompt, ending with the current user message; a system prompt is not a turn.
   */
  static boolean hasEarlierTurns(List<Message> history) {
    return history.stream()
            .filter(
                message ->
                    message.getMessageType() == MessageType.USER
                        || message.getMessageType() == MessageType.ASSISTANT)
            .count()
        > 1;
  }

  /**
   * Makes a post-processor judge documents against a follow-up's standalone rewrite rather than the
   * original question, which is what {@code RetrievalAugmentationAdvisor} passes it.
   */
  static DocumentPostProcessor judgedAgainstTheRewrite(DocumentPostProcessor postProcessor) {
    return (query, documents) ->
        postProcessor.process(
            query.context().get(STANDALONE_QUERY) instanceof String rewrite
                ? query.mutate().text(rewrite).build()
                : query,
            documents);
  }
}
