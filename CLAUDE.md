# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an AI-powered search application built with Spring Boot 3.5.7 and Java 21. The application integrates Apache Solr for both traditional keyword search and semantic vector search, with Anthropic Claude for intelligent query generation and OpenAI for vector embeddings.

### Core Architecture

The application follows a **package-by-feature** structure organized around two main domains:

- **Search Domain** (`dev.aparikh.aipoweredsearch.search`):
  - `SearchController`: REST endpoints for search operations
  - `SearchService`: Orchestrates traditional and semantic search with AI query generation
  - `SearchRepository`: Low-level Solr query execution and field introspection
  - Traditional search: Converts free-text queries into structured Solr queries using Claude AI
  - Semantic search: Uses vector embeddings (OpenAI) for similarity-based retrieval

- **Indexing Domain** (`dev.aparikh.aipoweredsearch.indexing`):
  - `IndexController`: REST endpoints for document indexing
  - `IndexService`: Manages document indexing with automatic vector embedding generation
  - Supports both single and batch document indexing

- **Vector Store** (`dev.aparikh.aipoweredsearch.solr.vectorstore`):
  - `SolrVectorStore`: Custom Spring AI VectorStore implementation for Solr 9.x+
  - Implements dense vector support with HNSW (Hierarchical Navigable Small World) algorithm
  - Handles automatic embedding generation and vector similarity search

- **Configuration** (`dev.aparikh.aipoweredsearch.config`):
  - `SpringAiConfig`: Multi-LLM configuration for Anthropic (chat) and OpenAI (embeddings)
  - `SolrConfig`: Solr client configuration with HttpSolrClient
  - Chat Memory: PostgreSQL-backed conversational context with conversation ID "007"

### Key Dependencies

- **Spring Boot 3.5.7** with Spring AI 1.1.0-M4
- **Anthropic Claude AI** (claude-sonnet-4-5) for query generation and chat
- **OpenAI** (text-embedding-3-small) for vector embeddings (1536 dimensions)
- **Apache Solr 9.9.0** for search and vector storage with dense vector support
- **PostgreSQL** for chat memory persistence
- **Testcontainers** for integration testing with Solr and PostgreSQL
- **SpringDoc OpenAPI** for API documentation

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
```

### Running Vector Store Tests
Vector store integration tests require a valid OpenAI API key:
```bash
export OPENAI_API_KEY="your-actual-api-key"
./gradlew test --tests "dev.aparikh.aipoweredsearch.config.SolrVectorStore*"
```

A helper script is provided: `./run-vector-tests.sh`

## Configuration Requirements

### Environment Variables
- `ANTHROPIC_API_KEY`: Required for Claude AI integration (query generation and chat)
- `OPENAI_API_KEY`: Required for OpenAI embeddings (vector search and indexing)
- `POSTGRES_USER`: PostgreSQL username (defaults to 'postgres')
- `POSTGRES_PASSWORD`: PostgreSQL password (defaults to 'postgres')

### External Services
- **Solr**: Expected at `http://localhost:8983/solr`
  - Must support dense vector fields (Solr 9.0+)
  - Collections must include vector field with DenseVectorField type
  - See Solr Schema Requirements section below
- **PostgreSQL**: Expected at `jdbc:postgresql://localhost:5432/chatmemory`
  - Used for chat memory persistence
- **Docker**: Required for running external services and Testcontainers tests

### Running with Docker Compose
```bash
docker-compose up -d  # Start Solr and PostgreSQL
docker-compose down   # Stop services
```

### API Documentation
- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **OpenAPI Spec**: http://localhost:8080/api-docs
- **Health Check**: http://localhost:8080/actuator/health

## Vector Search and Indexing

### Vector Store Implementation

The `SolrVectorStore` is a custom implementation that:
- Extends `AbstractObservationVectorStore` from Spring AI 1.1.0-M4
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

**Search Flow**:
1. Claude AI parses natural language filters and search intent
2. For semantic search: generates query embedding using OpenAI
3. Executes KNN similarity search in Solr (topK=10 by default)
4. Returns documents with similarity scores in metadata

**Implementation**: `SearchService` in `src/main/java/dev/aparikh/aipoweredsearch/search/`

## Testing Architecture

The project has comprehensive test coverage across three levels:

### Test Levels

1. **Unit Tests** (`@ExtendWith(MockitoExtension.class)` or `@WebMvcTest`)
   - Service layer tests: `SearchServiceTest`, `IndexServiceTest`
   - Controller layer tests: `SearchControllerTest`, `IndexControllerTest`
   - Fast execution, no external dependencies
   - Use mocked dependencies and static mocking for builders

2. **Integration Tests** (`@SpringBootTest` with Testcontainers)
   - Full application context tests: `SearchIntegrationTest`, `IndexIntegrationTest`
   - Uses Testcontainers for Solr and PostgreSQL
   - Tests complete request/response cycles
   - Verifies actual database interactions

3. **Vector Store Tests** (Requires OpenAI API key)
   - `SolrVectorStoreIT`: Tests vector store operations
   - `SolrVectorStoreObservationIT`: Tests observability features
   - Uses real embeddings (not mocked) when `OPENAI_API_KEY` is set
   - Tests skip gracefully if API key is not available

### Test Configuration
- Separate test configuration: `src/test/resources/application-test.properties`
- PostgreSQL test config: `PostgresTestConfiguration.java`
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

# Run with detailed output
./gradlew test --tests "SolrVectorStoreIT" --info
```

## Key Implementation Patterns

### Multi-LLM Configuration
When using both Anthropic and OpenAI, Spring AI cannot auto-configure `ChatClient`. Explicit configuration is required in `SpringAiConfig`:
```java
@Bean
public ChatClient chatClient(
    @Qualifier("anthropicChatModel") ChatModel chatModel,
    ChatMemory chatMemory
) {
    return ChatClient.builder(chatModel)
        .defaultAdvisors(new MessageChatMemoryAdvisor(chatMemory))
        .build();
}
```

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
│   └── SolrVectorStoreOptions
└── config/            # Cross-cutting configuration
```

## Important Implementation Details

### Solr Client Configuration
The project uses `HttpSolrClient` (HTTP/1.1) instead of `Http2SolrClient` to avoid Jetty 12.x dependency conflicts. Jetty HTTP/2 modules are explicitly excluded in `build.gradle.kts`.

### Vector Search POST Method
Vector searches use POST method to avoid "URI too long" errors when sending large embedding arrays (1536 dimensions).

### Observation and Metrics
The `SolrVectorStore` extends `AbstractObservationVectorStore` for integration with Micrometer:
- Tracks add/delete operations
- Monitors similarity search performance
- Provides metrics for observability platforms

### Chat Memory
- Conversation ID is hardcoded as "007" in the application
- PostgreSQL-backed for persistence across restarts
- Schema auto-initialization enabled via `spring.ai.chat.memory.repository.jdbc.initialize-schema=always`

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

### Jetty HTTP Protocol Violations
If you see "HTTP protocol violation: Authentication challenge without WWW-Authenticate header":
- This is a known Jetty 12.x issue with invalid API keys
- Verify your OpenAI API key is valid
- The error occurs when Jetty's HTTP/2 client encounters invalid auth

### Filter Expression Syntax
When creating vector store searches:
- Use Spring AI filter syntax: `category == 'AI'`
- NOT Solr syntax: `category:AI`
- The VectorStore converts expressions internally

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
