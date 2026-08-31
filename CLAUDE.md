# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an AI-powered search application built with Spring Boot 4.1.1 and Java 25. The application integrates Apache Solr for both traditional keyword search and semantic vector search, with Anthropic Claude for intelligent query generation and OpenAI for vector embeddings.

### Core Architecture

The application follows a **package-by-feature** structure organized around main domains:

- **Search Domain** (`dev.aparikh.aipoweredsearch.search`):
  - `SearchController`: REST endpoints for search operations
  - `SearchService`: Orchestrates traditional, semantic, and hybrid search with AI query generation
  - `SearchRepository`: Low-level Solr query execution and field introspection
  - Traditional search: Converts free-text queries into structured Solr queries using Claude AI
  - Semantic search: Uses vector embeddings (OpenAI) for similarity-based retrieval
  - Hybrid search: Client-side RRF (Reciprocal Rank Fusion) combining keyword and vector signals

- **Indexing Domain** (`dev.aparikh.aipoweredsearch.indexing`):
  - `IndexController`: REST endpoints for document indexing
  - `IndexService`: Manages document indexing with automatic vector embedding generation
  - Supports both single and batch document indexing

- **Vector Store** (`dev.aparikh.aipoweredsearch.solr.vectorstore`):
  - `SolrVectorStore`: Custom Spring AI VectorStore implementation for Solr 9.x+
  - `VectorStoreFactory`: Factory for creating VectorStore instances
  - Implements dense vector support with HNSW (Hierarchical Navigable Small World) algorithm
  - Handles automatic embedding generation and vector similarity search

- **RRF Fusion** (`dev.aparikh.aipoweredsearch.search`):
    - `RrfMerger`: Client-side Reciprocal Rank Fusion implementation
    - Merges keyword and vector search results using rank-based scoring
    - Formula: `score = sum(1 / (k + rank))` with configurable k parameter (default: 60)

- **Configuration** (`dev.aparikh.aipoweredsearch.config`):
    - `AiConfig`: Multi-LLM configuration for Anthropic (chat) and OpenAI (embeddings)
  - `SolrConfig`: Solr client configuration with HttpJdkSolrClient
    - `PromptCacheMetricsAdvisor`: Logs Anthropic prompt caching metrics
  - Chat Memory: PostgreSQL-backed conversational context with conversation ID "007"

### Key Dependencies

- **Spring Boot 4.1.1** with Spring AI 2.0.1
- **Anthropic Claude AI** (claude-sonnet-4-5) for query generation and chat
- **OpenAI** (text-embedding-3-small) for vector embeddings (1536 dimensions)
- **SolrJ 10.0.0** client against **Apache Solr 9.10.0** server, with dense vector support
- **ZooKeeper 3.9** for SolrCloud coordination
- **PostgreSQL 16** for chat memory persistence
- **Testcontainers** for integration testing with Solr, PostgreSQL, and Ollama
- **SpringDoc OpenAPI** for API documentation

### Schema-Agnostic Design

The hybrid RRF search implementation is **schema-agnostic**, meaning it works with any Solr collection without requiring
specific field names:

- **Uses `_text_` catch-all field**: Solr's built-in field that aggregates all text content from all text fields
- **No field assumptions**: Doesn't require specific fields like `title`, `content`, or `category`
- **Graceful degradation**: Works even if your schema has custom field names
- **Easy integration**: Drop-in search for any existing Solr collection
- **Dynamic metadata**: Uses `metadata_*` dynamic fields pattern for flexible document attributes

This makes the search functionality portable across different schemas and use cases. The application discovers available
fields at runtime and adapts accordingly.

### Advanced Solr Features

The application leverages advanced Apache Solr features for enhanced search quality and user experience:

- **Client-side RRF (Reciprocal Rank Fusion)**: Balanced fusion of keyword and vector search using client-side merging
- **Highlighting**: Shows users why results matched their query
- **Faceting**: Filter and aggregate results by category, year, etc.
- **Spell Checking**: "Did you mean...?" suggestions for misspelled queries
- **Enhanced Field Boosting**: Title^5, Tags^3, Content^2 with phrase boosting
- **Synonym Expansion**: Domain-specific synonyms for AI, programming, frameworks, databases, cloud

**See [SOLR_ENHANCEMENTS.md](SOLR_ENHANCEMENTS.md) for detailed documentation.**

## Anthropic Prompt Caching

The application implements Anthropic's prompt caching feature to reduce costs by up to 90% and improve response times by
up to 85% for subsequent requests with identical prompts.

### Configuration

Prompt caching is configured via application properties and can be controlled with environment variables:

```properties
# Enable/disable prompt caching (default: true)
spring.ai.anthropic.prompt-caching.enabled=${ANTHROPIC_PROMPT_CACHING_ENABLED:true}
# Cache strategy (default: SYSTEM_AND_TOOLS)
spring.ai.anthropic.prompt-caching.strategy=${ANTHROPIC_PROMPT_CACHING_STRATEGY:SYSTEM_AND_TOOLS}
```

### Available Cache Strategies

| Strategy                 | Description                        | Best For                                                   |
|--------------------------|------------------------------------|------------------------------------------------------------|
| **NONE**                 | Disables caching                   | One-off requests with no repeated content                  |
| **SYSTEM_ONLY**          | Caches system prompts only         | Stable system prompts with <20 tools                       |
| **TOOLS_ONLY**           | Caches tool definitions only       | Large tool sets (5000+ tokens) with dynamic system prompts |
| **SYSTEM_AND_TOOLS**     | Caches both independently          | Applications with 20+ tools (default)                      |
| **CONVERSATION_HISTORY** | Caches entire conversation history | Multi-turn conversations with ChatClient memory            |

### Cache Metrics Logging

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

### Implementation Details

- **Location**: `src/main/java/dev/aparikh/aipoweredsearch/config/`
    - `AiConfig.java`: Configures AnthropicChatOptions with caching
    - `PromptCacheMetricsAdvisor.java`: Logs cache metrics as a CallAdvisor

- **How it works**:
    - Cache options are configured when creating ChatClient beans
    - The advisor intercepts all chat responses and extracts cache metrics from Anthropic API usage data
    - Metrics include: cache creation tokens, cache read tokens, regular input tokens, and output tokens
    - Cost savings are calculated based on Anthropic's pricing (cache reads are 90% cheaper)

- **Supported models**: Claude Opus 4, Claude Sonnet 4, Claude Sonnet 3.7, Claude Sonnet 3.5, Claude Haiku 3.5, Claude
  Haiku 3

- **Token requirements**:
    - Claude Sonnet 4: 1024+ tokens minimum for caching
    - Claude Haiku models: 2048+ tokens minimum
    - Other models: 1024+ tokens minimum

### Disabling Prompt Caching

To disable prompt caching:

```bash
# Via environment variable
export ANTHROPIC_PROMPT_CACHING_ENABLED=false

# Or in application.properties
spring.ai.anthropic.prompt-caching.enabled=false
```

When disabled, no cache options are set and the advisor will not log cache metrics.

## Development Commands

### Build and Run
```bash
./gradlew build                 # Build the project
./gradlew bootRun              # Run the application
./gradlew clean build          # Clean build
./gradlew bootBuildImage       # Build Docker image
```

### Testing
```bash
./gradlew test                                    # Run all tests
./gradlew check                                  # Run all checks including tests
./gradlew test --tests "ClassName"              # Run specific test class
./gradlew test --tests "dev.aparikh.aipoweredsearch.search.*"  # Run all search tests
./gradlew test --tests "dev.aparikh.aipoweredsearch.indexing.*" # Run all indexing tests
```

### Test Execution Examples
```bash
# Integration tests (require containers)
./gradlew test --tests "SearchIntegrationTest"
./gradlew test --tests "IndexIntegrationTest"
./gradlew test --tests "SolrVectorStoreIT"

# Unit tests (fast, no containers)
./gradlew test --tests "SearchServiceTest"
./gradlew test --tests "IndexServiceTest"
./gradlew test --tests "SearchControllerTest"
./gradlew test --tests "IndexControllerTest"

# Vector store tests (require OPENAI_API_KEY)
./gradlew test --tests "SolrVectorStoreIT" --info
./gradlew test --tests "SolrVectorStoreObservationIT" --info

# Specific search types
./gradlew test --tests "SemanticAndHybridSearchIntegrationTest"
./gradlew test --tests "SearchRepositoryIT"
```

### Code Quality and Coverage

```bash
# Generate code coverage report
./gradlew test jacocoTestReport
open build/reports/jacoco/test/html/index.html

# Run SonarQube analysis (requires running SonarQube server)
./gradlew sonarqube \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.token=your-token

# Generate Javadoc
./gradlew javadoc
open build/docs/javadoc/index.html
```

**Quality Metrics Tracked:**

- Code coverage via JaCoCo (XML and HTML reports)
- Code smells and technical debt
- Security vulnerabilities (OWASP Top 10)
- Maintainability and reliability ratings
- Exclusions: Application classes, Config classes, model/dto packages

### Helper Scripts

The repository includes several helper scripts for common tasks:

**`run-vector-tests.sh`**: Run vector store tests with proper environment setup
```bash
./run-vector-tests.sh
```

- Checks if OPENAI_API_KEY is set
- Runs SolrVectorStoreIT and SolrVectorStoreObservationIT
- Displays test results summary
- Provides HTML report location

**`init-solr.sh`**: Initialize Solr with custom schema (runs via Docker Compose)

- Starts Solr in SolrCloud mode with ZooKeeper
- Uploads custom configset to ZooKeeper
- Creates "books" collection with vector field configuration
- Configures 1536-dimensional vector field with HNSW and cosine similarity
- Auto-runs when starting docker-compose

**`fix-evaluation-tests.sh`**: Fix evaluation tests (development utility)

## Configuration Requirements

### Environment Variables
- `ANTHROPIC_API_KEY`: Required for Claude AI integration (query generation and chat)
- `ANTHROPIC_PROMPT_CACHING_ENABLED`: Enable Anthropic prompt caching (defaults to 'true')
- `ANTHROPIC_PROMPT_CACHING_STRATEGY`: Cache strategy (defaults to 'SYSTEM_AND_TOOLS')
- `OPENAI_API_KEY`: Required for OpenAI embeddings (vector search and indexing)
- `POSTGRES_USER`: PostgreSQL username (defaults to 'postgres')
- `POSTGRES_PASSWORD`: PostgreSQL password (defaults to 'postgres')

### External Services
- **Solr**: Expected at `http://localhost:8983/solr`
  - Must support dense vector fields (Solr 9.0+)
  - Collections must include vector field with DenseVectorField type
  - Managed via SolrCloud with ZooKeeper coordination
  - See Solr Schema Requirements section below
- **ZooKeeper**: Expected at `localhost:2181` (internal, managed by Docker)
    - Coordinates SolrCloud cluster
    - Stores collection configurations
- **PostgreSQL**: Expected at `jdbc:postgresql://localhost:5432/chatmemory`
  - Used for chat memory persistence
- **Docker**: Required for running external services and Testcontainers tests

### Running with Docker Compose
```bash
docker-compose up -d  # Start Solr, ZooKeeper, and PostgreSQL
docker-compose down   # Stop services
docker-compose ps     # Check service status
docker-compose logs solr  # View Solr logs
```

**Services started:**

- Solr 9.10.0 on port 8983 (with ZooKeeper coordination)
- ZooKeeper 3.9 on port 2181
- PostgreSQL 16 on port 5432

**Volumes:**

- `solr_data`: Persistent Solr data
- `postgres_data`: Persistent PostgreSQL data
- `./solr-config`: Custom Solr schema configuration
- `./mydata`: Sample data for indexing

### API Versioning

Endpoints use the **path-segment API versioning** built into Spring Framework 7 / Spring Boot 4,
rather than hardcoding a version into each mapping:

```properties
spring.mvc.apiversion.use.path-segment=1
spring.mvc.apiversion.default=v1
```

Controllers declare the version segment as a URI variable and the version as a mapping attribute:

```java
@RequestMapping(path = "/api/{version}/search", version = "v1")
```

- **URLs are unchanged** — `/api/v1/search/...` works exactly as before. `SemanticApiVersionParser`
  skips leading non-digits, so `v1` parses as `1.0.0`.
- **Adding a version** is a new mapping with `version = "v2"`, not a new path. Spring routes on the
  segment; baseline versions (`"1.2+"`) match that version and anything higher.
- **Unsupported versions return 400** (`InvalidApiVersionException`). The supported set is detected
  from the mappings themselves via `detect-supported`, which defaults to true.
- **Non-versioned paths are unaffected.** `/actuator/**` and `/api-docs` are served by separate
  handler mappings that never consult the version strategy, so path segment 1 not being a version
  there is harmless. `ApiVersioningTest` pins this, since it would otherwise be an easy regression.

### API Documentation
- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **OpenAPI Spec**: http://localhost:8080/api-docs
- **Health Check**: http://localhost:8080/actuator/health

## Vector Search and Indexing

### Vector Store Implementation

The `SolrVectorStore` is a custom implementation that:

- Extends `AbstractObservationVectorStore` from Spring AI 2.0.1
- Implements the `VectorStore` interface for document storage and similarity search
- Uses Solr's `DenseVectorField` type for KNN (K-Nearest Neighbors) search with HNSW algorithm
- Supports cosine similarity metric for vector comparison
- Automatically generates embeddings using OpenAI's text-embedding-3-small model (1536 dimensions)
- Includes observability support via Micrometer for tracking operations
- Uses POST method for search queries to avoid URI length limitations
- Handles Solr's multi-valued fields with proper type conversion

**Location**: `src/main/java/dev/aparikh/aipoweredsearch/solr/vectorstore/SolrVectorStore.java`

### Solr Schema Requirements

For vector search to work, Solr collections must have the following configuration:

```xml
<!-- Core fields -->
<field name="id" type="string" indexed="true" stored="true" required="true"/>
<field name="content" type="text_general" indexed="true" stored="true"/>
<field name="vector" type="knn_vector_1536" indexed="true" stored="true"/>

<!-- Vector field type definition -->
<fieldType name="knn_vector_1536" class="solr.DenseVectorField"
           vectorDimension="1536"
           similarityFunction="cosine"
           knnAlgorithm="hnsw"/>

<!-- Dynamic metadata fields for document attributes -->
<dynamicField name="metadata_*" type="text_general" indexed="true" stored="true"/>
```

The vector field type must match the embedding dimension (1536 for text-embedding-3-small).

### Indexing API

Documents can be indexed with automatic embedding generation via REST endpoints:

**Single Document**: `POST /api/v1/index/{collection}`
```json
{
  "id": "doc1",
  "content": "Your document text here",
  "metadata": {
    "author": "John Doe",
    "category": "tech",
    "tags": ["java", "spring", "ai"]
  }
}
```

**Batch Indexing**: `POST /api/v1/index/{collection}/batch`
```json
{
  "documents": [
    {
      "id": "doc1",
      "content": "First document",
      "metadata": {"category": "tech"}
    },
    {
      "content": "Document without ID (auto-generated UUID)",
      "metadata": {"category": "science"}
    }
  ]
}
```

**Features**:
- Auto-generates UUIDs if document ID is not provided
- Handles null or empty metadata gracefully
- Supports complex metadata (nested objects, arrays, various types)
- Processes Unicode and special characters correctly
- Efficient batch processing for multiple documents

**Implementation**: `IndexService` in `src/main/java/dev/aparikh/aipoweredsearch/indexing/`

### Search APIs

**Traditional Search**: `GET /api/v1/search/{collection}?query={natural_language_query}`
- Uses Claude AI to generate structured Solr queries (q, fq, sort, fl, facets)
- Best for exact matches, filtering, faceting, and traditional search operations
- Returns results based on keyword matching and Solr scoring
- Example: "find spring boot documents from 2024, group by category"

**Semantic Search**: `GET /api/v1/search/{collection}/semantic?query={text}`
- Uses vector embeddings and cosine similarity for semantic matching
- Best for finding conceptually similar documents
- Returns results ranked by similarity score
- Combines AI-powered filter parsing with vector similarity
- Example: "comfortable running shoes under $100"

**Hybrid Search (RRF)**:
`GET /api/v1/search/{collection}/hybrid?query={text}&k={topK}&minScore={threshold}&fields={csv}`

- Uses client-side RRF (Reciprocal Rank Fusion) to combine keyword and vector signals
- Executes keyword search and vector search independently, then merges on client side
- Best for balanced search that leverages both exact matches and semantic understanding
- Schema-agnostic: works with any Solr collection using `_text_` catch-all field
- Intelligent fallback: hybrid → keyword-only → vector-only if no results
- Parameters:
    - `k`: topK results (defaults to 100)
    - `minScore`: minimum RRF score threshold
    - `fields`: comma-separated list of fields to return
- Example: "machine learning frameworks" (finds both exact term matches and semantically similar content)

**RAG Question Answering**: `POST /api/v1/search/ask`

Request body:

```json
{
  "question": "What are the benefits of Spring Boot?",
  "conversationId": "optional-session-id"
}
```

Response:

```json
{
  "answer": "Generated answer based on retrieved context",
  "conversationId": "session-id",
  "sources": ["doc-1", "doc-2"]
}
```

- Uses RetrievalAugmentationAdvisor with HybridDocumentRetriever to retrieve relevant documents
  via hybrid search (keyword + vector, RRF-fused)
- Claude generates conversational answers based on retrieved context
- Maintains conversation history for follow-up questions
- Reduces hallucinations by grounding answers in indexed documents
- Example: "How does dependency injection work in Spring?"

**Search Flow**:
1. Claude AI parses natural language filters and search intent
2. For semantic/hybrid search: generates query embedding using OpenAI
3. Executes search in Solr:
    - Traditional: BM25 keyword search
   - Semantic: KNN similarity search (topK=50 by default)
   - Hybrid: Client-side RRF merging keyword and vector results
4. For RAG: RetrievalAugmentationAdvisor retrieves hybrid context and injects into prompt
5. Returns documents with scores and enhanced features (highlighting, facets, spell check)

**Implementation**:

- `SearchService` in `src/main/java/dev/aparikh/aipoweredsearch/search/`
- `SearchRepository.executeHybridRerankSearch()` for hybrid search orchestration
- `RrfMerger` for client-side RRF algorithm implementation
- `SearchService.ask()` for RAG question answering
- `AiConfig.ragChatClient` bean with RetrievalAugmentationAdvisor configuration
- `HybridDocumentRetriever` adapting hybrid search to Spring AI's `DocumentRetriever` SPI

## Testing Architecture

The project has comprehensive test coverage across four levels:

### Test Levels

1. **Unit Tests** (`@ExtendWith(MockitoExtension.class)` or `@WebMvcTest`)
   - Service layer tests: `SearchServiceTest`, `IndexServiceTest`
   - Controller layer tests: `SearchControllerTest`, `IndexControllerTest`
   - Fast execution, no external dependencies
   - Use mocked dependencies and static mocking for builders

2. **Integration Tests** (`@SpringBootTest` with Testcontainers)
   - Full application context tests: `SearchIntegrationTest`, `IndexIntegrationTest`
   - Search functionality tests: `SemanticAndHybridSearchIntegrationTest`, `RagQuestionAnswerIntegrationTest`
   - Uses Testcontainers for Solr, ZooKeeper, and PostgreSQL
   - Tests complete request/response cycles
   - Verifies actual database interactions
   - Requires ANTHROPIC_API_KEY and OPENAI_API_KEY for semantic/hybrid/RAG tests

3. **Vector Store Tests** (Requires OpenAI API key)
   - `SolrVectorStoreIT`: Tests vector store operations
   - `SolrVectorStoreObservationIT`: Tests observability features
   - Uses real embeddings (not mocked) when `OPENAI_API_KEY` is set
   - Tests skip gracefully if API key is not available

4. **Evaluation Tests** (LLM-based testing)
    - `EvaluationTestBase`: Base class for LLM evaluation tests
    - Tests RAG quality, question answering accuracy
    - Uses Ollama (via Testcontainers) for local LLM testing
    - Configuration in `EvaluationModelsTestConfiguration.java`

### Test Configuration
- Separate test configuration: `src/test/resources/application-test.properties`
- PostgreSQL test config: `PostgresTestConfiguration.java`
- Solr test config: `SolrTestConfiguration.java`
- Evaluation models config: `EvaluationModelsTestConfiguration.java`
- Mock embeddings for integration tests (1536-dimensional float arrays)
- Awaitility for async operation verification in Solr

### Running Tests
```bash
# Run all tests
./gradlew test

# Run tests by package
./gradlew test --tests "dev.aparikh.aipoweredsearch.search.*"
./gradlew test --tests "dev.aparikh.aipoweredsearch.indexing.*"

# Run specific test classes
./gradlew test --tests "SearchIntegrationTest"
./gradlew test --tests "IndexServiceTest"

# Run semantic/hybrid search integration tests (requires API keys)
./gradlew test --tests "SemanticAndHybridSearchIntegrationTest"

# Run RAG integration tests (requires API keys)
./gradlew test --tests "RagQuestionAnswerIntegrationTest"

# Run with detailed output
./gradlew test --tests "SolrVectorStoreIT" --info

# Run vector store tests with helper script
./run-vector-tests.sh
```

### Running Vector Store Tests

Vector store integration tests require a valid OpenAI API key:

```bash
export OPENAI_API_KEY="your-actual-api-key"
./gradlew test --tests "dev.aparikh.aipoweredsearch.solr.vectorstore.*"
```

A helper script is provided: `./run-vector-tests.sh`

### Running Prompt Cache Tests

Prompt caching integration tests require a valid Anthropic API key:

```bash
export ANTHROPIC_API_KEY="your-actual-api-key"
./gradlew test --tests "PromptCacheMetricsAdvisorIT" --info
```

The test validates:

- Cache MISS on first request (cache creation)
- Cache HIT on subsequent identical requests
- Correct cache metrics logging
- Cost savings calculations

## Key Implementation Patterns

### Multi-LLM Configuration

When using both Anthropic and OpenAI, Spring AI cannot auto-configure `ChatClient`. Explicit configuration is required
in `AiConfig`:
```java
@Bean
@Qualifier("searchChatClient")
public ChatClient chatClient(
        ChatModel chatModel,
    ChatMemory chatMemory
) {
    return ChatClient.builder(chatModel)
        .defaultAdvisors(new MessageChatMemoryAdvisor(chatMemory))
        .build();
}
```

**Note**: The configuration class is `AiConfig.java`, not `SpringAiConfig`.

### Vector Store Builder Pattern
`SolrVectorStore` uses the builder pattern extending `AbstractVectorStoreBuilder`:
```java
SolrVectorStore vectorStore = SolrVectorStore.builder(solrClient, collection, embeddingModel)
    .options(SolrVectorStoreOptions.defaults())
    .build();
```

### Spring AI Filter Expression Syntax
The vector store uses Spring AI's filter expression language:
```java
SearchRequest request = SearchRequest.builder()
    .query("search text")
    .topK(10)
    .similarityThreshold(0.7)
    .filterExpression("category == 'AI'")  // Not Solr syntax
    .build();
```

The filter expression is converted to Solr query format internally.

### Embedding Storage
Embeddings are stored in Document metadata with key "embedding". The VectorStore automatically generates embeddings for documents without them during `add()` operations.

### Metadata Handling
Solr returns multi-valued fields as lists. The vector store extracts first values and converts types:
```java
// Handles: {"category": ["AI"]} -> {"category": "AI"}
// Handles: {"year": [2024L]} -> {"year": 2024}
```

### Package Structure
Code is organized by **feature** (not layer):
```
dev.aparikh.aipoweredsearch/
├── search/              # Search domain
│   ├── SearchController
│   ├── SearchService
│   ├── SearchRepository
│   └── model/          # Search-specific models
├── indexing/           # Indexing domain
│   ├── IndexController
│   ├── IndexService
│   └── model/         # Indexing-specific models
├── solr/vectorstore/  # Vector store implementation
│   ├── SolrVectorStore
│   ├── VectorStoreFactory
│   └── SolrVectorStoreOptions
├── embedding/         # Embedding utilities
│   ├── EmbeddingService
│   └── VectorFormatUtils
└── config/            # Cross-cutting configuration
    ├── AiConfig
    ├── SolrConfig
    └── PromptCacheMetricsAdvisor
```

## Important Implementation Details

### Solr Client Configuration
The project uses `HttpJdkSolrClient` for improved performance with HTTP/2 features including multiplexing, header
compression, and better connection management. The client is configured with appropriate timeouts for production use.

**Note**: SolrJ 10 removed the Jetty-backed `Http2SolrClient` and replaced it with `HttpJdkSolrClient`, which is built on
the JDK's `java.net.http.HttpClient`. SolrJ therefore no longer pulls in Jetty, and the previous Jetty 11.x version
pinning and exclusions have been removed from `build.gradle.kts`.

### Vector Search POST Method
Vector searches use POST method to avoid "URI too long" errors when sending large embedding arrays (1536 dimensions).

### Hybrid Search with Client-Side RRF

The `executeHybridRerankSearch()` method in `SearchRepository` implements client-side RRF:

```java
// Step 1: Execute keyword search independently
List<Map<String, Object>> keywordResults = executeKeywordSearch(
                collection, query, topK * 2, filterExpression, fieldsCsv);

// Step 2: Execute vector search independently
List<Map<String, Object>> vectorResults = executeVectorSearch(
        collection, query, topK * 2, filterExpression, fieldsCsv);

// Step 3: Merge using RRF algorithm
RrfMerger rrfMerger = new RrfMerger(); // Uses default k=60
List<Map<String, Object>> mergedResults = rrfMerger.merge(keywordResults, vectorResults);
```

**Key features**:

- Keyword and vector legs execute **concurrently** on virtual threads, so a hybrid query
  costs roughly one Solr round-trip instead of two
- Uses client-side RRF merging via `RrfMerger` class
- RRF formula: `score = sum(1 / (k + rank))` with configurable k parameter (default: 60)
- Executes keyword (edismax) and vector (KNN) searches independently
- Fetches `topK * 2` results from each search for better fusion quality
- Schema-agnostic using `_text_` catch-all field
- Intelligent fallback: hybrid → keyword → vector if no results
- Applies minScore filtering and topK limiting after fusion

### Observation and Metrics
The `SolrVectorStore` extends `AbstractObservationVectorStore` for integration with Micrometer:
- Tracks add/delete operations
- Monitors similarity search performance
- Provides metrics for observability platforms

### RAG Configuration

The RAG (Retrieval-Augmented Generation) feature uses a dedicated `ragChatClient` bean configured in `AiConfig`:

RAG retrieval uses **hybrid search** (keyword + vector, fused with RRF), not vector similarity
alone. This requires the `spring-ai-rag` dependency:

```kotlin
implementation("org.springframework.ai:spring-ai-rag")
```

```java

@Bean
@Qualifier("ragChatClient")
public ChatClient ragChatClient(ChatModel chatModel,
                                ChatMemory chatMemory,
                                HybridDocumentRetriever hybridDocumentRetriever,
                                ...) {
    return ChatClient.builder(chatModel)
            .defaultAdvisors(
                    RetrievalAugmentationAdvisor.builder()
                            .documentRetriever(hybridDocumentRetriever)
                            // Pass-through joiner: the default ConcatenationDocumentJoiner
                            // re-sorts by score and would undo the RRF ranking.
                            .documentJoiner(documentsForQuery -> documentsForQuery.values().stream()
                                    .flatMap(List::stream)
                                    .flatMap(List::stream)
                                    .toList())
                            .build(),
                    MessageChatMemoryAdvisor.builder(chatMemory).build(),
                    SimpleLoggerAdvisor.builder().build(),
                    PromptCacheMetricsAdvisor.builder()
                            .cachingEnabled(cachingEnabled)
                            .build()
            )
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
- **Field projection**: `id,content,metadata_*` — excludes the 1536-dim `vector` field, which
  Solr would otherwise return on every hit under the default `fl=*`

**Sources**: `AskResponse.sources` reports the IDs of documents actually placed in the prompt
context, read from `RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT` in the response context.
`SearchService.ask()` therefore requests `.call().chatClientResponse()` rather than
`.call().content()`.

### Chat Memory

- Conversation ID defaults to "default" if not provided in AskRequest
- PostgreSQL-backed for persistence across restarts
- Schema auto-initialization enabled via `spring.ai.chat.memory.repository.jdbc.initialize-schema=always`
- Allows multi-turn conversations where context is maintained

### Virtual Threads
The application uses Java virtual threads (`spring.threads.virtual.enabled=true`) for improved concurrency handling.

## Common Issues and Solutions

### "Only DenseVectorField is compatible with Vector Query Parsers"
If you see this error: `Error from server at http://localhost:8983/solr: only DenseVectorField is compatible with Vector Query Parsers`

**Cause**: The Solr collection doesn't have the proper vector field configuration.

**Solution**:
1. Restart Docker containers to run the initialization script:
   ```bash
   docker-compose down
   docker-compose up -d
   ```

2. The `init-solr.sh` script will automatically create collections with proper vector field configuration:
   - Field type: `knn_vector_1536` (1536 dimensions)
   - Field name: `vector`
   - Similarity function: cosine
   - Algorithm: HNSW

3. Verify the field configuration:
   ```bash
   curl "http://localhost:8983/solr/books/schema/fields/vector"
   ```

4. If using a custom collection name, ensure it's created with the vector field or modify `init-solr.sh` to add your collection name.

### Vector Store Tests Require API Key
If vector store tests fail with "Cannot invoke EmbeddingResponse.getResults() because embeddingResponse is null":
- Set valid `OPENAI_API_KEY` environment variable
- Tests use real embeddings, not mocks
- Use `./run-vector-tests.sh` helper script

### Jetty HTTP Protocol Violations (no longer applicable)
Earlier versions of this project could fail with "HTTP protocol violation: Authentication challenge without
WWW-Authenticate header" when an invalid OpenAI API key was used, because both SolrJ and Spring AI routed HTTP traffic
through Jetty.

Jetty is no longer on the classpath: SolrJ 10 uses the JDK `HttpClient` and Spring AI 2.x uses the official vendor SDKs.
If OpenAI calls fail, verify that `OPENAI_API_KEY` contains a valid key.

### Filter Expression Handling

The application uses two different approaches for filter handling depending on the layer:

#### SearchService Layer (Semantic Search via SearchRepository)

**Uses Solr-native filter syntax** generated by Claude AI:

- Claude AI generates filters like: `"metadata_year:[2020 TO *]"`, `"category:tech"`, `"status:active"`
- `SearchService.semanticSearch()` passes these directly to `SearchRepository.semanticSearch()`
- `SearchRepository` applies filters using `solrQuery.addFilterQuery()` without parsing
- **Why**: Spring AI's `FilterExpressionTextParser` cannot handle Solr range syntax `[min TO max]`
- **Benefit**: Full Solr query syntax support including ranges, Boolean operators, wildcards

Example flow:

```java
// 1. User query: "AI articles from 2020 onwards"
// 2. Claude generates: fq=["metadata_year:[2020 TO *]"]
// 3. SearchService passes to SearchRepository directly
// 4. SearchRepository applies: solrQuery.addFilterQuery("metadata_year:[2020 TO *]")
```

#### SolrVectorStore Layer (Direct VectorStore API)

**Uses Spring AI filter expression syntax**:

- Spring AI filter syntax: `category == 'AI'`, `year == '2024'`
- `SolrVectorStore.convertFilterToSolrQuery()` converts to Solr syntax: `metadata_category:AI`
- **Why**: When using `VectorStore` interface directly (e.g., in tests), must use Spring AI syntax
- **Limitation**: Only supports simple equality expressions `key == 'value'`, not ranges

Example flow:

```java
// Direct VectorStore usage
SearchRequest request = SearchRequest.builder()
    .query("search text")
    .topK(10)
    .filterExpression("category == 'AI'")  // Spring AI syntax
    .build();
vectorStore.similaritySearch(request);
// Internally converted to: metadata_category:AI
```

#### Summary

- **SearchService → SearchRepository**: Solr-native syntax (full Solr capabilities)
- **Direct VectorStore API**: Spring AI syntax (simple equality only)
- **Why dual approach**: SearchService needs advanced Solr filters that Spring AI parser cannot handle

### Test Container Port Conflicts
If Testcontainers fail to start:
- Ensure Docker Desktop is running
- Check for port conflicts with existing Solr/PostgreSQL instances
- Testcontainers uses random ports to avoid conflicts

## Related Documentation

- Spring AI Documentation: https://docs.spring.io/spring-ai/reference/
- Apache Solr Documentation: https://solr.apache.org/guide/
- Testcontainers Documentation: https://www.testcontainers.org/
- OpenAPI/Swagger UI: Available at `/swagger-ui.html` when application is running
