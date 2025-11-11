# Vector Search Implementation Guide

This guide provides comprehensive documentation on the vector store implementation, semantic search, and indexing capabilities in the AI-Powered Search application.

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Vector Store Implementation](#vector-store-implementation)
4. [Embedding Generation](#embedding-generation)
5. [Indexing Documents](#indexing-documents)
6. [Semantic Search](#semantic-search)
7. [Keyword Search](#keyword-search)
8. [Configuration](#configuration)
9. [API Reference](#api-reference)
10. [Examples](#examples)

## Overview

The AI-Powered Search application combines traditional keyword search with modern semantic search capabilities using vector embeddings. It leverages:

- **Apache Solr** for storage and vector similarity search
- **OpenAI embeddings** (text-embedding-3-small) for semantic representation
- **Anthropic Claude** (claude-sonnet-4-5) for intelligent query generation
- **Spring AI** for abstraction and integration

### Key Features

- **Semantic Search**: Find documents by meaning, not just keywords
- **Hybrid Search**: Combine semantic similarity with keyword filters
- **Auto-Indexing**: Automatic embedding generation during document indexing
- **Batch Operations**: Efficient batch indexing with automatic embedding
- **Type-Safe Schema**: Explicitly typed metadata fields (strings, integers, booleans, etc.)
- **Conversational Context**: Claude maintains conversation history for query refinement

## Architecture

### Component Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        Client Application                        │
└───────────────┬─────────────────────────┬──────────────────────┘
                │                         │
        Indexing API              Search API
                │                         │
    ┌───────────▼──────────┐   ┌─────────▼────────────┐
    │  IndexController     │   │  SearchController    │
    │  /api/v1/index       │   │  /api/v1/search      │
    └───────────┬──────────┘   └─────────┬────────────┘
                │                        │
    ┌───────────▼──────────┐   ┌─────────▼────────────┐
    │   IndexService       │   │   SearchService      │
    │  - Batch indexing    │   │  - Query generation  │
    │  - Auto embeddings   │   │  - Semantic search   │
    └───────────┬──────────┘   └─────────┬────────────┘
                │                        │
                │              ┌─────────▼────────────┐
                │              │  SearchRepository    │
                │              │  - Solr queries      │
                │              │  - Schema introspect │
                │              └─────────┬────────────┘
                │                        │
    ┌───────────▼────────────────────────▼────────────┐
    │           SolrVectorStore                       │
    │  - Embedding integration                        │
    │  - Vector similarity search                     │
    │  - Document persistence                         │
    └───────────┬─────────────────────────────────────┘
                │
    ┌───────────▼──────────┐   ┌──────────────────────┐
    │  OpenAI Embeddings   │   │  Anthropic Claude    │
    │  text-embedding-3-   │   │  claude-sonnet-4-5   │
    │  small (1536-dim)    │   │  Query generation    │
    └──────────────────────┘   └──────────────────────┘
                │
    ┌───────────▼──────────────────────────────────────┐
    │              Apache Solr 9.9.0                   │
    │  - DenseVectorField (HNSW, cosine)              │
    │  - KNN search                                    │
    │  - Typed schema fields                           │
    └──────────────────────────────────────────────────┘
```

### Data Flow

#### Indexing Flow

```
1. Client sends document(s) → IndexController
2. IndexService prepares documents
3. SolrVectorStore generates embeddings (if not provided)
   - Calls OpenAI text-embedding-3-small
   - Generates 1536-dimensional vectors
4. Documents + embeddings stored in Solr
   - Content in "content" field
   - Vector in "vector" field (knn_vector_1536)
   - Metadata in typed fields (author, category, etc.)
5. Response with success/failure details
```

#### Semantic Search Flow

```
1. Client sends natural language query → SearchController
2. SearchService generates query embedding
   - Calls OpenAI to vectorize query text
3. Claude AI generates filter queries (optional)
   - Analyzes user intent
   - Generates field selections and filters
4. SearchRepository executes KNN search
   - Solr query: {!knn f=vector topK=10}[embedding]
   - Applies filters and field selections
5. Results ranked by cosine similarity
6. Response with documents and similarity scores
```

#### Keyword Search Flow

```
1. Client sends search request → SearchController
2. SearchService retrieves Solr schema
   - Gets field types and capabilities
3. Claude AI generates full Solr query
   - Query string (q parameter)
   - Filter queries (fq parameter)
   - Sort, fields, facets
4. SearchRepository executes Solr query
5. Response with matched documents
```

## Vector Store Implementation

### SolrVectorStore

The `SolrVectorStore` class extends Spring AI's `AbstractObservationVectorStore` to provide Solr-backed vector storage.

**Location**: `src/main/java/dev/aparikh/aipoweredsearch/solr/vectorstore/SolrVectorStore.java`

#### Key Features

- **Automatic Embedding Generation**: Generates embeddings for documents without vectors
- **Batch Processing**: Efficient batch embedding and indexing
- **Metadata Handling**: Stores metadata with configurable prefix
- **Type Safety**: Uses configured field names for id, content, and vector
- **Observation**: Integrates with Spring's observation framework for monitoring

#### Configuration

Configured via `SolrVectorStoreOptions` record:

```java
public record SolrVectorStoreOptions(
    String idFieldName,          // Default: "id"
    String contentFieldName,     // Default: "content"
    String vectorFieldName,      // Default: "vector"
    String metadataFieldPrefix,  // Default: "metadata_"
    int vectorDimension          // Default: 1536
)
```

#### Builder Pattern

```java
SolrVectorStore vectorStore = SolrVectorStore.builder(solrClient, collection, embeddingModel)
    .options(SolrVectorStoreOptions.defaults())
    .build();
```

#### Core Methods

**Adding Documents**:
```java
public void add(List<Document> documents)
```
- Generates embeddings for documents without vectors
- Stores documents with metadata in Solr
- Single commit for batch operations

**Similarity Search**:
```java
public List<Document> similaritySearch(SearchRequest request)
```
- Converts query text to embedding
- Executes KNN search in Solr
- Returns documents ranked by similarity

**Deleting Documents**:
```java
public Optional<Boolean> delete(List<String> idList)
```
- Deletes documents by ID
- Returns true if successful

### Vector Field in Solr

**Schema Definition** (`solr-config/conf/managed-schema.xml`):

```xml
<!-- Field Type -->
<fieldType name="knn_vector_1536"
           class="solr.DenseVectorField"
           vectorDimension="1536"
           similarityFunction="cosine"
           knnAlgorithm="hnsw"/>

<!-- Field -->
<field name="vector"
       type="knn_vector_1536"
       indexed="true"
       stored="true"/>
```

**Key Properties**:
- **Algorithm**: HNSW (Hierarchical Navigable Small World) - approximate KNN
- **Similarity**: Cosine similarity (range: 0-1, where 1 is most similar)
- **Dimensions**: 1536 (matches OpenAI text-embedding-3-small)
- **Indexed**: Enables KNN search
- **Stored**: Returns vectors in search results

## Embedding Generation

### OpenAI Text-Embedding-3-Small

**Configuration** (`SpringAiConfig.java`):

```java
@Bean
public OpenAiEmbeddingModel embeddingModel(OpenAiApi openAiApi) {
    return new OpenAiEmbeddingModel(
        openAiApi,
        MetadataMode.EMBED,
        OpenAiEmbeddingOptions.builder()
            .withModel("text-embedding-3-small")
            .withDimensions(1536)
            .build()
    );
}
```

**Properties**:
- **Model**: text-embedding-3-small
- **Dimensions**: 1536
- **Cost**: ~$0.02 per 1M tokens
- **Context**: 8,191 tokens
- **Performance**: Fast, suitable for real-time search

### Embedding Process

**During Indexing**:
```java
// In IndexService.indexDocument()
Document document = Document.builder()
    .id(request.id() != null ? request.id() : UUID.randomUUID().toString())
    .content(request.content())
    .metadata(metadata)
    .build();

// SolrVectorStore automatically generates embedding if not present
vectorStore.add(List.of(document));
```

**During Search**:
```java
// In SearchService.semanticSearch()
SearchRequest searchRequest = SearchRequest.query(query)
    .withTopK(10)
    .withSimilarityThreshold(0.7);

// Embedding generated automatically from query text
List<Document> results = vectorStore.similaritySearch(searchRequest);
```

### Batch Embedding Optimization

The `EmbeddingModel.embedForResponse()` method processes multiple texts in a single API call:

```java
// In SolrVectorStore.add()
List<String> contents = documentsWithoutEmbedding.stream()
    .map(Document::getContent)
    .toList();

EmbeddingResponse response = embeddingModel.embedForResponse(contents);
List<Embedding> embeddings = response.getResults();
```

**Benefits**:
- Reduced API calls
- Lower latency
- Cost-effective for batch operations

## Indexing Documents

### IndexService

**Location**: `src/main/java/dev/aparikh/aipoweredsearch/indexing/IndexService.java`

#### Single Document Indexing

```java
public IndexResponse indexDocument(String collection, IndexRequest request)
```

**Process**:
1. Generate UUID if ID not provided
2. Build Document with content and metadata
3. Create SolrVectorStore for collection
4. Add document (embeddings auto-generated)
5. Return response with indexed count

**Example Request**:
```json
{
  "id": "doc-123",
  "content": "Spring Boot is a powerful framework for building Java applications",
  "metadata": {
    "category": "framework",
    "author": "John Doe",
    "tags": ["java", "spring", "backend"],
    "priority": 5,
    "published": true,
    "rating": 4.5
  }
}
```

#### Batch Document Indexing

```java
public IndexResponse indexDocuments(String collection, BatchIndexRequest request)
```

**Process**:
1. Convert all requests to Documents
2. Generate UUIDs for documents without IDs
3. Create SolrVectorStore for collection
4. Add all documents in single operation
5. Return response with success/failure details

**Benefits**:
- Single embedding API call for all documents
- Single Solr commit
- Atomic operation (all or nothing)

**Example Request**:
```json
{
  "documents": [
    {
      "id": "doc-1",
      "content": "First document content",
      "metadata": {"category": "tech"}
    },
    {
      "content": "Second document (auto-ID)",
      "metadata": {"category": "science"}
    }
  ]
}
```

### Metadata Mapping

Metadata fields are mapped to Solr fields based on type:

**Explicit Fields** (defined in schema):
- **Strings**: `author`, `category`, `type`, `language`, `size`, `test`
- **Integers**: `priority`, `order`
- **Double**: `rating`
- **Boolean**: `published`
- **String Array**: `tags`

**Dynamic Fields** (fallback):
- Pattern: `metadata_*`
- Type: `text_general`
- Stored: true
- Indexed: true
- Multi-valued: true

**Mapping Example**:
```java
Map<String, Object> metadata = new HashMap<>();
metadata.put("author", "John Doe");         // → author (string)
metadata.put("priority", 5);                 // → priority (pint)
metadata.put("tags", List.of("java", "ai")); // → tags (strings)
metadata.put("custom_field", "value");       // → metadata_custom_field (text_general)
```

## Semantic Search

### SearchService

**Location**: `src/main/java/dev/aparikh/aipoweredsearch/search/SearchService.java`

#### Semantic Search Method

```java
public SearchResponse semanticSearch(String collection, String query,
                                     Integer topK, Double minScore)
```

**Process**:
1. Build SearchRequest with query, topK, similarity threshold
2. Generate query embedding (automatic via SolrVectorStore)
3. Execute KNN search in Solr
4. Convert Documents to SearchResult objects
5. Return results with similarity scores

**Query Example**:
```
GET /api/v1/search/books/semantic?query=machine learning frameworks&topK=10&minScore=0.7
```

**Solr Query Generated**:
```
{!knn f=vector topK=10}[0.123, -0.456, ..., 0.789]  // 1536 values
```

#### With Filters (Claude-Enhanced)

When using the combined search endpoint with semantic mode, Claude AI can generate filters:

**Request**:
```json
{
  "query": "recent machine learning papers about neural networks",
  "collection": "books",
  "searchType": "SEMANTIC",
  "topK": 10,
  "minScore": 0.75
}
```

**Claude Analysis**:
```json
{
  "filterQueries": [
    "category:research",
    "published:true",
    "tags:(neural OR networks)"
  ],
  "fieldList": ["id", "title", "author", "content", "rating"]
}
```

**Final Solr Query**:
```
q={!knn f=vector topK=10}[embeddings...]
fq=category:research
fq=published:true
fq=tags:(neural OR networks)
fl=id,title,author,content,rating
```

### Similarity Scoring

**Cosine Similarity** (range: 0-1):
- **1.0**: Identical vectors (perfect match)
- **0.9-1.0**: Very similar
- **0.7-0.9**: Moderately similar
- **0.5-0.7**: Somewhat related
- **0.0-0.5**: Less related

**Filtering by Score**:
```java
// Only return documents with similarity >= 0.7
SearchRequest.query(query)
    .withTopK(10)
    .withSimilarityThreshold(0.7);
```

**Score in Response**:
```json
{
  "results": [
    {
      "id": "doc-123",
      "content": "Document content...",
      "metadata": {...},
      "score": 0.89  // Cosine similarity
    }
  ]
}
```

## Keyword Search

### SearchService

#### Keyword Search Method

```java
public SearchResponse search(SearchRequest request)
```

**Process**:
1. Retrieve Solr schema fields with types
2. Send schema + query to Claude AI
3. Claude generates complete Solr query
4. Execute query via SearchRepository
5. Parse and return results

**System Prompt** (`src/main/resources/prompts/system-message.st`):
- Guides Claude on field types and capabilities
- Provides Solr query syntax examples
- Instructs on faceting, sorting, filtering

#### Claude Query Generation

**Input to Claude**:
```json
{
  "query": "find java books published after 2020 with high ratings",
  "fields": [
    {"name": "category", "type": "string"},
    {"name": "published", "type": "boolean"},
    {"name": "rating", "type": "pdouble"},
    {"name": "content", "type": "text_general"}
  ]
}
```

**Claude Response**:
```json
{
  "queryString": "content:(java AND books)",
  "filterQueries": [
    "published:true",
    "rating:[4.0 TO *]"
  ],
  "sort": "rating desc",
  "fieldList": ["id", "title", "author", "rating", "content"],
  "facetFields": ["category", "author"],
  "rows": 10
}
```

**Solr Query Executed**:
```
q=content:(java AND books)
fq=published:true
fq=rating:[4.0 TO *]
sort=rating desc
fl=id,title,author,rating,content
facet=true
facet.field=category
facet.field=author
rows=10
```

### Field Type Awareness

Claude understands different field types and generates appropriate queries:

**Text Fields** (`text_general`):
```
content:(machine learning)
content:"exact phrase"
content:(java OR python)
```

**String Fields** (`string`):
```
category:technology
author:"John Doe"
```

**Numeric Fields** (`pint`, `pdouble`):
```
priority:[5 TO *]
rating:[4.0 TO 5.0]
```

**Boolean Fields** (`boolean`):
```
published:true
archived:false
```

**Date Fields** (`pdate`):
```
created_date:[2020-01-01T00:00:00Z TO *]
```

**Array Fields** (`strings`):
```
tags:(java OR spring OR boot)
```

## Configuration

### Application Properties

**Location**: `src/main/resources/application.properties`

```properties
# Solr Configuration
spring.ai.vectorstore.solr.host=http://localhost:8983/solr

# Anthropic Claude Configuration
spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}
spring.ai.anthropic.chat.options.model=claude-sonnet-4-5

# OpenAI Configuration
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.embedding.options.model=text-embedding-3-small

# PostgreSQL Chat Memory
spring.datasource.url=jdbc:postgresql://localhost:5432/chatmemory
spring.datasource.username=${POSTGRES_USER:postgres}
spring.datasource.password=${POSTGRES_PASSWORD:postgres}

# Application Settings
spring.threads.virtual.enabled=true
```

### Environment Variables

Required:
```bash
export ANTHROPIC_API_KEY="sk-ant-..."
export OPENAI_API_KEY="sk-..."
```

Optional:
```bash
export POSTGRES_USER="postgres"
export POSTGRES_PASSWORD="postgres"
```

### Docker Services

**Start Services**:
```bash
docker-compose up -d
```

**Services**:
- **Solr**: http://localhost:8983/solr
- **ZooKeeper**: localhost:2181 (internal)
- **PostgreSQL**: localhost:5432

## API Reference

### Indexing Endpoints

#### Index Single Document

```http
POST /api/v1/index/{collection}
Content-Type: application/json

{
  "id": "doc-123",  // Optional (UUID generated if omitted)
  "content": "Document text content",
  "metadata": {
    "author": "John Doe",
    "category": "technology",
    "tags": ["java", "spring"],
    "priority": 5,
    "published": true,
    "rating": 4.5
  }
}
```

**Response**:
```json
{
  "indexed": 1,
  "failed": 0,
  "documentIds": ["doc-123"],
  "message": "Successfully indexed 1 document(s)"
}
```

#### Batch Index Documents

```http
POST /api/v1/index/{collection}/batch
Content-Type: application/json

{
  "documents": [
    {
      "id": "doc-1",
      "content": "First document",
      "metadata": {"category": "tech"}
    },
    {
      "content": "Second document (auto-ID)",
      "metadata": {"category": "science"}
    }
  ]
}
```

**Response**:
```json
{
  "indexed": 2,
  "failed": 0,
  "documentIds": ["doc-1", "a1b2c3d4-..."],
  "message": "Successfully indexed 2 document(s)"
}
```

### Search Endpoints

#### Semantic Search

```http
GET /api/v1/search/{collection}/semantic
  ?query=machine learning frameworks
  &topK=10
  &minScore=0.7
```

**Response**:
```json
{
  "query": "machine learning frameworks",
  "results": [
    {
      "id": "doc-123",
      "content": "TensorFlow and PyTorch are popular ML frameworks...",
      "metadata": {
        "category": "technology",
        "author": "Jane Smith"
      },
      "score": 0.89
    }
  ],
  "totalResults": 5
}
```

#### Keyword Search

```http
POST /api/v1/search
Content-Type: application/json

{
  "query": "find java books with high ratings",
  "collection": "books",
  "searchType": "KEYWORD"
}
```

**Response**:
```json
{
  "query": "find java books with high ratings",
  "results": [
    {
      "id": "book-456",
      "content": "Effective Java by Joshua Bloch...",
      "metadata": {
        "author": "Joshua Bloch",
        "rating": 4.8,
        "category": "programming"
      },
      "score": 12.5  // Solr relevance score
    }
  ],
  "totalResults": 15
}
```

## Examples

### Example 1: Index Documents with Embeddings

```java
// Prepare documents
List<IndexRequest> documents = List.of(
    new IndexRequest(
        "spring-boot-guide",
        "Spring Boot simplifies Java development with auto-configuration",
        Map.of("category", "framework", "language", "java")
    ),
    new IndexRequest(
        "python-ml-intro",
        "Python is widely used for machine learning with libraries like scikit-learn",
        Map.of("category", "ml", "language", "python")
    )
);

// Index batch
BatchIndexRequest batch = new BatchIndexRequest(documents);
IndexResponse response = indexService.indexDocuments("books", batch);

// Response: indexed=2, failed=0
```

### Example 2: Semantic Search

```java
// Search for conceptually similar documents
SearchResponse response = searchService.semanticSearch(
    "books",
    "frameworks for building web applications",
    10,   // topK
    0.7   // minimum similarity
);

// Results ranked by similarity
for (SearchResult result : response.results()) {
    System.out.println(result.id() + ": " + result.score());
    // spring-boot-guide: 0.85
    // django-tutorial: 0.78
    // ...
}
```

### Example 3: Keyword Search with Filters

```java
// Complex search with Claude AI
SearchRequest request = new SearchRequest(
    "find recent machine learning papers with high citations",
    "books",
    SearchType.KEYWORD
);

SearchResponse response = searchService.search(request);

// Claude generates:
// q=content:(machine AND learning AND papers)
// fq=category:research
// fq=citations:[100 TO *]
// sort=citations desc
```

### Example 4: Hybrid Search

Combine semantic similarity with keyword filters:

```java
// 1. Semantic search for relevant documents
SearchResponse semanticResults = searchService.semanticSearch(
    "books",
    "deep learning optimization techniques",
    50,
    0.6
);

// 2. Apply keyword filters via Claude
SearchRequest filterRequest = new SearchRequest(
    "filter by published:true AND category:research",
    "books",
    SearchType.KEYWORD
);

// Results: Semantically relevant + filtered by criteria
```

### Example 5: Custom Metadata

```java
// Index with custom metadata (uses dynamic fields)
IndexRequest request = new IndexRequest(
    "custom-doc",
    "Document with custom metadata fields",
    Map.of(
        "author", "John Doe",              // → author (string)
        "custom_department", "Engineering", // → metadata_custom_department
        "custom_cost", 1500.50,            // → metadata_custom_cost
        "custom_tags", List.of("a", "b")   // → metadata_custom_tags
    )
);

indexService.indexDocument("books", request);
```

## Best Practices

### Indexing

1. **Batch Operations**: Use batch indexing for multiple documents
2. **Meaningful IDs**: Provide descriptive IDs (e.g., "book-isbn-123")
3. **Rich Metadata**: Include relevant metadata for filtering
4. **Content Quality**: Ensure clean, meaningful text content

### Search

1. **Semantic for Concepts**: Use semantic search for conceptual queries
2. **Keyword for Precision**: Use keyword search for exact matches
3. **Appropriate topK**: Balance between recall and performance
4. **Similarity Threshold**: Set appropriate minScore (0.7-0.8 typically good)

### Performance

1. **Batch Embeddings**: Process multiple documents together
2. **Field Selection**: Request only needed fields (fl parameter)
3. **Pagination**: Use offset/limit for large result sets
4. **Caching**: Leverage Solr caching for frequent queries

### Monitoring

1. **Observation Framework**: Monitor vector store operations
2. **API Costs**: Track OpenAI embedding API usage
3. **Query Performance**: Monitor Solr query times
4. **Error Handling**: Log and handle failures gracefully

## Troubleshooting

### Common Issues

**Issue**: "Only DenseVectorField is compatible with Vector Query Parsers"
- **Cause**: Collection doesn't have vector field configured
- **Solution**: Restart containers to recreate schema

**Issue**: Low similarity scores
- **Cause**: Query doesn't match document semantics
- **Solution**: Rephrase query, lower similarity threshold

**Issue**: Slow indexing
- **Cause**: Individual document indexing
- **Solution**: Use batch indexing

**Issue**: Empty search results
- **Cause**: Similarity threshold too high
- **Solution**: Lower minScore or check query relevance

### Debugging

**Check Vector Field**:
```bash
curl "http://localhost:8983/solr/books/schema/fields/vector"
```

**View Embeddings**:
```bash
curl "http://localhost:8983/solr/books/select?q=*:*&fl=id,vector&rows=1"
```

**Test KNN Query**:
```bash
curl "http://localhost:8983/solr/books/select" \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "{!knn f=vector topK=5}[0.1,0.1,...]",
    "limit": 5
  }'
```

## Additional Resources

- [Apache Solr Documentation](https://solr.apache.org/guide/)
- [Spring AI Documentation](https://docs.spring.io/spring-ai/reference/)
- [OpenAI Embeddings Guide](https://platform.openai.com/docs/guides/embeddings)
- [HNSW Algorithm](https://arxiv.org/abs/1603.09320)
- [Cosine Similarity](https://en.wikipedia.org/wiki/Cosine_similarity)
