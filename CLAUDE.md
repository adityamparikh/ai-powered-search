# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an AI-powered search application built with Spring Boot 3.5.7 and Java 25. The application integrates Apache Solr for both traditional keyword search and semantic vector search, with Anthropic Claude for intelligent query generation and OpenAI for vector embeddings.

### Core Architecture

- **Spring Boot Application**: Main entry point at `dev.aparikh.aipoweredsearch.AiPoweredSearchApplication`
- **Search Service**: `SearchService` provides both traditional and semantic search
  - Traditional search: Converts free-text queries into structured Solr queries using Claude AI
  - Semantic search: Uses vector embeddings (OpenAI) for similarity-based retrieval
- **Search Repository**: `SearchRepository` handles Solr interactions and query execution
- **Index Service**: `IndexService` manages document indexing with automatic vector embedding generation
- **Solr Vector Store**: `SolrVectorStore` implements Spring AI VectorStore interface for Solr 9.x+ dense vector support
- **Chat Memory**: Uses PostgreSQL-backed chat memory for conversational context with conversation ID "007"
- **Configuration**: Multi-LLM configuration in `SpringAiConfig` for Anthropic (chat) and OpenAI (embeddings)

### Key Dependencies

- Spring Boot 3.5.7 with Spring AI 1.1.0-M4
- Anthropic Claude AI (claude-sonnet-4-5 model) for query generation
- OpenAI (text-embedding-3-small) for vector embeddings (1536 dimensions)
- Apache Solr 9.9.0 for search and vector storage
- PostgreSQL for chat memory storage
- Testcontainers for integration testing
- SpringDoc OpenAPI for API documentation

## Development Commands

### Build and Run
```bash
./gradlew build                 # Build the project
./gradlew bootRun              # Run the application
./gradlew clean build          # Clean build
```

### Testing
```bash
./gradlew test                 # Run all tests
./gradlew check               # Run all checks including tests
```

### Single Test Execution
```bash
./gradlew test --tests "ClassName"
./gradlew test --tests "dev.aparikh.aipoweredsearch.search.SearchIntegrationTest"
```

## Configuration Requirements

### Environment Variables
- `ANTHROPIC_API_KEY`: Required for Claude AI integration (query generation)
- `OPENAI_API_KEY`: Required for OpenAI embeddings (vector search)
- `POSTGRES_USER`: PostgreSQL username (defaults to 'postgres')
- `POSTGRES_PASSWORD`: PostgreSQL password (defaults to 'postgres')

### External Services
- **Solr**: Expected at `http://localhost:8983/solr`
  - Must support dense vector fields (Solr 9.0+)
  - Schema must include vector field with DenseVectorField type
- **PostgreSQL**: Expected at `jdbc:postgresql://localhost:5432/chatmemory`

### API Documentation
- Swagger UI available at `/swagger-ui.html`
- OpenAPI docs at `/api-docs`

## Semantic Search and Vector Indexing

### Vector Store Implementation

The application includes a custom `SolrVectorStore` implementation that:
- Extends `AbstractObservationVectorStore` from Spring AI 1.1.0-M4
- Implements the `VectorStore` interface for document storage and similarity search
- Uses Solr's dense vector field type for KNN (K-Nearest Neighbors) search
- Supports cosine similarity metric for vector comparison
- Automatically generates embeddings using OpenAI's text-embedding-3-small model
- Includes observation/metrics support via Micrometer

**Location**: `src/main/java/dev/aparikh/aipoweredsearch/config/SolrVectorStore.java`

### Solr Schema Requirements

For vector search to work, Solr collections must have the following field configuration:

```xml
<!-- Core fields -->
<field name="id" type="string" indexed="true" stored="true" required="true"/>
<field name="content" type="text_general" indexed="true" stored="true"/>
<field name="vector" type="knn_vector_1536" indexed="true" stored="true"/>

<!-- Vector field type definition -->
<fieldType name="knn_vector_1536" class="solr.DenseVectorField"
           vectorDimension="1536" similarityFunction="cosine"/>

<!-- Dynamic metadata fields -->
<dynamicField name="metadata_*" type="text_general" indexed="true" stored="true"/>
```

### Indexing API

Documents can be indexed with automatic embedding generation:

**Single Document**: `POST /api/v1/index/{collection}`
```json
{
  "id": "doc1",
  "content": "Your document text here",
  "metadata": {
    "author": "John Doe",
    "date": "2025-01-01"
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
      "id": "doc2",
      "content": "Second document",
      "metadata": {"category": "science"}
    }
  ]
}
```

The `IndexService` automatically:
1. Generates vector embeddings using OpenAI
2. Stores documents with embeddings in Solr
3. Handles batch operations efficiently

**Location**: `src/main/java/dev/aparikh/aipoweredsearch/indexing/`

### Semantic Search API

**Endpoint**: `GET /api/v1/search/{collection}/semantic?query={text}`

Semantic search performs the following steps:
1. Uses Claude AI to parse natural language filters from the query
2. Generates vector embedding for the query text using OpenAI
3. Executes KNN similarity search in Solr (topK=10, cosine similarity)
4. Returns semantically similar documents ranked by similarity score

**Example**:
```
GET /api/v1/search/products/semantic?query=comfortable running shoes under $100
```

Response includes documents with similarity scores in metadata.

**Location**: `SearchService.semanticSearch()` in `src/main/java/dev/aparikh/aipoweredsearch/search/service/SearchService.java`

### Traditional vs Semantic Search

- **Traditional Search** (`GET /api/v1/search/{collection}?query={text}`):
  - Uses Claude AI to generate structured Solr queries (q, fq, sort, fl, facets)
  - Best for exact matches, filtering, faceting, and traditional search operations
  - Returns results based on keyword matching and Solr scoring

- **Semantic Search** (`GET /api/v1/search/{collection}/semantic?query={text}`):
  - Uses vector embeddings and cosine similarity
  - Best for finding conceptually similar documents
  - Returns results based on semantic meaning, not just keywords
  - Combines AI-powered filter parsing with vector similarity

## Testing Architecture

The project uses Testcontainers for integration testing with:
- Solr containers for search testing
- PostgreSQL containers for chat memory testing
- Mock configurations for Claude AI in tests
- Separate test configurations in `application-test.properties`

### Test Structure
- `SearchIntegrationTest`: End-to-end search functionality
- `SearchServiceTest`: Unit tests for search service
- `SearchRepositoryIT`: Integration tests for Solr repository
- `SolrTestBase`: Base class for Solr-related tests

## Key Patterns

- **Multi-LLM Integration**: Uses multiple AI models (Anthropic Claude for chat, OpenAI for embeddings) with explicit bean configuration in `SpringAiConfig`
- **AI-Powered Query Transformation**: Natural language to structured Solr queries using Claude AI
- **Vector Search with Spring AI**: Custom `SolrVectorStore` implementing Spring AI's VectorStore interface
- **Automatic Embedding Generation**: Documents are automatically vectorized during indexing using OpenAI
- **Conversational Search**: Persistent chat memory with PostgreSQL for context-aware interactions
- **Hybrid Search Capabilities**: Both traditional keyword search and semantic vector search
- **Field Introspection**: Dynamic query building based on Solr schema
- **Package by Feature**: Code organized by feature (indexing, search) rather than by layer
- **Comprehensive Testing**: Integration tests with Testcontainers for Solr and PostgreSQL

## Important Implementation Notes

### Multi-LLM Configuration
When using both Anthropic and OpenAI dependencies, Spring AI cannot auto-configure `ChatClient`. Explicit configuration is required in `SpringAiConfig` with `@Qualifier` annotations to distinguish between models.

### Vector Store Builder Pattern
`SolrVectorStore` uses the builder pattern extending `AbstractVectorStoreBuilder`:
```java
SolrVectorStore vectorStore = SolrVectorStore.builder(solrClient, collection, embeddingModel)
    .options(SolrVectorStoreOptions.defaults())
    .build();
```

### Embedding Storage
Embeddings are stored in Document metadata with the key "embedding". The VectorStore automatically generates embeddings for documents that don't have them during the `add()` operation.

### Search Request API
Spring AI 1.1.0-M4 uses a builder pattern for SearchRequest:
```java
SearchRequest request = SearchRequest.builder()
    .query("search text")
    .topK(10)
    .filterExpression("field:value")
    .build();
```