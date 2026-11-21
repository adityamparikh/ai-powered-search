# RAG pipeline (`/ask`) configuration

The beans, properties and design reasons behind `/api/v1/search/ask`: retrieval, query
rewrite, decisions screening, reranking, chat memory. The sequence diagram is in
[architecture.md](architecture.md); how changes are measured is in
[rag-evaluation.md](rag-evaluation.md). Routed from CLAUDE.md.

## Multi-LLM Configuration

When using both Anthropic and OpenAI, Spring AI cannot auto-configure `ChatClient`. Explicit configuration is required
in `AiConfig`:
```java
@Bean
public ChatClient searchChatClient(ChatModel chatModel, ...) {
    // Stateless: query generation keeps no chat memory. Only ragChatClient (/ask) has a
    // MessageChatMemoryAdvisor, keyed by the request's conversationId.
    return ChatClient.builder(chatModel)
        .defaultAdvisors(SimpleLoggerAdvisor.builder().build(), ...)
        .build();
}
```

**Note**: The configuration class is `AiConfig.java`, not `SpringAiConfig`.

## RAG Configuration

The RAG (Retrieval-Augmented Generation) feature uses a dedicated `ragChatClient` bean configured in `AiConfig`:

RAG retrieval uses **hybrid search** (keyword + vector, fused with RRF), not vector similarity
alone. This requires the `spring-ai-rag` dependency:

```kotlin
implementation("org.springframework.ai:spring-ai-rag")
```

Abridged from `AiConfig.ragChatClient` (read the method for the full wiring and its comments):

```java
@Bean
public ChatClient ragChatClient(ChatModel chatModel, ChatMemory chatMemory,
                                HybridDocumentRetriever hybridDocumentRetriever,
                                QueryTransformer queryTransformer, /* reranker, decisions beans, flags */ ...) {
    RetrievalAugmentationAdvisor.Builder ragAdvisor = RetrievalAugmentationAdvisor.builder()
            .queryTransformers(queryTransformer)           // follow-up -> standalone query
            .documentRetriever(hybridDocumentRetriever)    // Solr /hybrid, RRF
            .queryAugmenter(ContextualQueryAugmenter.builder().allowEmptyContext(true).build())
            // Pass-through joiner: the default ConcatenationDocumentJoiner re-sorts by score
            // and would undo the RRF ranking.
            .documentJoiner(documentsForQuery -> documentsForQuery.values().stream()
                    .flatMap(List::stream).flatMap(List::stream).toList());
    // decisions filter (optional), then the reranker (Claude, or the decisions reranker),
    // each wrapped with judgedAgainstTheRewrite(...)
    ragAdvisor.documentPostProcessors(postProcessors);
    return ChatClient.builder(chatModel)
            .defaultAdvisors(ragAdvisor.build(),
                    MessageChatMemoryAdvisor.builder(chatMemory).build(),
                    SimpleLoggerAdvisor.builder().build(),
                    PromptCacheMetricsAdvisor.builder().cachingEnabled(cachingEnabled).build())
            .build();
}
```

**Key components**:

- **RetrievalAugmentationAdvisor**: Spring AI's modular RAG advisor; delegates retrieval to a
  `DocumentRetriever` and injects the results into the prompt
- **HybridDocumentRetriever**: Implements `DocumentRetriever` over
  `SearchRepository.executeHybridRerankSearch()`. Calls the repository directly, deliberately
  skipping `SearchService`'s Claude query-generation step — that is worth its latency for a
  search API but not on a RAG turn, where the model already has the question.
- **Pass-through DocumentJoiner**: Required. The default `ConcatenationDocumentJoiner` re-sorts
  documents by their own score, which would discard the fused RRF ordering.
- **MessageChatMemoryAdvisor**: Maintains conversation context across multiple questions
- **SimpleLoggerAdvisor**: Logs prompts and responses for debugging
- **PromptCacheMetricsAdvisor**: Tracks Anthropic prompt caching metrics
- **Collection**: `solr.default.collection` property (defaults to "books")
- **RerankingDocumentPostProcessor**: Asks Claude to judge the retrieved candidates against the
  question, reorder them, and discard the rest. This is the third and last place the pipeline can
  improve context quality — the retriever decides what is a *candidate*, reranking decides what
  actually reaches the prompt.
- **Retrieval depth**: `search.rag.hybrid.top-k` (defaults to 20). This is a *candidate* count,
  not a context size: reranking is expected to trim it. Reranking earns its keep by discarding,
  so retrieval must over-fetch — reranking N documents down to N only reorders them.
- **Reranking**: `search.rag.rerank.enabled` (defaults to true), `search.rag.rerank.top-k`
  (defaults to 5). Disabling it avoids a second model call per RAG turn, but then every
  retrieved chunk goes straight into the prompt — lower `search.rag.hybrid.top-k` if you do.
  The reranking prompt is unique per question, so it does not benefit from prompt caching.
  Failures are never fatal: an unavailable model, a malformed ranking, or indexes pointing
  nowhere all degrade to the retriever's RRF order.
- **Conversation-aware retrieval**: Spring AI's `CompressionQueryTransformer` rewrites each
  question, with the conversation so far, into a standalone query before retrieval, so a follow-up
  such as "Anything cheaper by the same author?" is searched with the author filled in. It runs on
  `search.rag.query-rewrite.model` (default `claude-haiku-4-5`). See Craig Walls' *Making RAG
  Conversation-Aware* recipe. Two additions to the recipe: a first question, which has no
  conversation to fold in, is searched as asked; and the reranker judges candidates against the
  rewrite (left in the query context under `rag.standalone`), because `RetrievalAugmentationAdvisor`
  hands post-processors the original question. The shared `"default"` conversation (requests
  without a `conversationId`) is not rewritten, since its history mixes unrelated callers, and a
  failed rewrite falls back to the question as asked.
- **Decisions (opt-in, pluggable)**: a decisions provider answers narrow questions about each
  retrieved passage with calibrated probabilities instead of generated text.
  `search.rag.decisions.enabled=true` screens candidates before reranking (prompt injection,
  relevance, answer evidence); `search.rag.rerank.provider=decisions` replaces the Claude reranker,
  keeping the `search.rag.rerank.top-k` passages most likely to answer and discarding only those below
  `search.rag.decisions.rerank.minimum-score` (default 0). Both judge a follow-up's standalone rewrite.
  - **Pluggable**: `search.rag.decisions.provider` (default `typesafe`) selects the provider. A
    provider is a `@Configuration` contributing beans named `decisionsFilter` and `decisionsReranker`
    (`AiConfig.DECISIONS_FILTER`, `DECISIONS_RERANKER`); `AiConfig` wires them without knowing the
    provider. Another provider, such as OpenAI's Decisions API, would be a new configuration.
  - **`typesafe`** (`TypeSafeDecisionsConfig`): `JevDocumentFilter` and `JevDocumentReranker` on the
    TypeSafe starter's client: hosted Jev, or a local model with the same API (Laya, Nimble) via
    `spring.ai.typesafe.base-url` and `spring.ai.typesafe.model`. The key comes from
    `TYPESAFE_API_KEY` (`spring.ai.typesafe.api-key=${TYPESAFE_API_KEY:false}`); a local model needs
    only a placeholder. The starter activates when that property is present and not `false`, and an
    empty key fails startup, so an unset variable resolves to `false` and TypeSafe stays off.
  - **Off by default**: one call per passage adds latency, and hosted Jev sends passages to TypeSafe.
    A passage the provider cannot judge is passed through with a WARN, so screening is best-effort.
    Asking for a decisions stage that no provider supplies (no key, a typo) logs a WARN: passages are
    not screened, or Claude reranks.
- **Field projection**: `id,content,metadata_*` — excludes the 1536-dim `vector` field, which
  Solr would otherwise return on every hit under the default `fl=*`

**Sources**: `AskResponse.sources` reports the IDs of documents actually placed in the prompt
context, read from `RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT` in the response context.
`SearchService.ask()` therefore requests `.call().chatClientResponse()` rather than
`.call().content()`.

## Chat Memory

- Conversation ID defaults to "default" if not provided in AskRequest
- PostgreSQL-backed for persistence across restarts
- Schema auto-initialization enabled via `spring.ai.chat.memory.repository.jdbc.initialize-schema=always`
- Allows multi-turn conversations where context is maintained

## Virtual Threads
The application uses Java virtual threads (`spring.threads.virtual.enabled=true`) for improved concurrency handling.
