# Hybrid search, the vector store and filters

How keyword, vector and hybrid search reach Solr: the schema the vector store needs, the
Solr-native RRF request, the fallbacks, and the two filter syntaxes. Diagrams are in
[architecture.md](architecture.md). Routed from CLAUDE.md.

## Schema-Agnostic Design

The hybrid RRF search implementation is **schema-agnostic**, meaning it works with any Solr collection without requiring
specific field names:

- **Uses `_text_` catch-all field**: Solr's built-in field that aggregates all text content from all text fields
- **No field assumptions**: Doesn't require specific fields like `title`, `content`, or `category`
- **Graceful degradation**: Works even if your schema has custom field names
- **Easy integration**: Drop-in search for any existing Solr collection
- **Dynamic metadata**: Uses `metadata_*` dynamic fields pattern for flexible document attributes

This makes the search functionality portable across different schemas and use cases. The application discovers available
fields at runtime and adapts accordingly.

## Advanced Solr Features

The application leverages advanced Apache Solr features for enhanced search quality and user experience:

- **Solr-native RRF (Reciprocal Rank Fusion)**: Balanced fusion of keyword and vector search inside Solr (combined query, Solr 10.1+ or 9.11+)
- **Highlighting**: Shows users why results matched their query
- **Faceting**: Filter and aggregate results by category, year, etc.
- **Spell Checking**: "Did you mean...?" suggestions for misspelled queries
- **Enhanced Field Boosting**: Title^5, Tags^3, Content^2 with phrase boosting
- **Synonym Expansion**: Domain-specific synonyms for AI, programming, frameworks, databases, cloud

**See [SOLR_ENHANCEMENTS.md](SOLR_ENHANCEMENTS.md) for detailed documentation.**

## Vector Store Implementation

The `SolrVectorStore` is a custom implementation that:

- Extends `AbstractObservationVectorStore` from Spring AI 2.0.2
- Implements the `VectorStore` interface for document storage and similarity search
- Uses Solr's `DenseVectorField` type for KNN (K-Nearest Neighbors) search with HNSW algorithm
- Supports cosine similarity metric for vector comparison
- Automatically generates embeddings using OpenAI's text-embedding-3-small model (1536 dimensions)
- Includes observability support via Micrometer for tracking operations
- Uses POST method for search queries to avoid URI length limitations
- Handles Solr's multi-valued fields with proper type conversion

**Location**: `src/main/java/dev/aparikh/aipoweredsearch/solr/vectorstore/SolrVectorStore.java`

## Solr Schema Requirements

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

## Vector Store Builder Pattern
`SolrVectorStore` uses the builder pattern extending `AbstractVectorStoreBuilder`:
```java
SolrVectorStore vectorStore = SolrVectorStore.builder(solrClient, collection, embeddingModel)
    .options(SolrVectorStoreOptions.defaults())
    .build();
```

## Spring AI Filter Expression Syntax
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

## Embedding Storage
Embeddings are stored in Document metadata with key "embedding". The VectorStore automatically generates embeddings for documents without them during `add()` operations.

## Metadata Handling
Solr returns multi-valued fields as lists. The vector store extracts first values and converts types:
```java
// Handles: {"category": ["AI"]} -> {"category": "AI"}
// Handles: {"year": [2024L]} -> {"year": 2024}
```

## Solr Client Configuration
The project uses `HttpJdkSolrClient` for improved performance with HTTP/2 features including multiplexing, header
compression, and better connection management. The client is configured with appropriate timeouts for production use.

**Note**: SolrJ 10 removed the Jetty-backed `Http2SolrClient` and replaced it with `HttpJdkSolrClient`, which is built on
the JDK's `java.net.http.HttpClient`. SolrJ therefore no longer pulls in Jetty, and the previous Jetty 11.x version
pinning and exclusions have been removed from `build.gradle.kts`.

## Vector Search POST Method
Vector searches use POST method to avoid "URI too long" errors when sending large embedding arrays (1536 dimensions).

## Hybrid Search with Solr-Native RRF

`executeHybridRerankSearch()` in `SearchRepository` sends **one** JSON request to the collection's
`/hybrid` handler (`solr.CombinedQuerySearchHandler`, Solr 10.1+ and 9.11+, SOLR-17319). Solr runs both legs
and fuses them with RRF; the application does no merging:

```json
POST /solr/{collection}/hybrid
{
  "queries": {
    "keyword": { "edismax": { "query": "...", "qf": "_text_" } },
    "vector":  { "knn": { "f": "vector", "topK": 20, "query": "[...]" } }
  },
  "limit": 10,
  "filter": ["..."],
  "params": {
    "combiner": true,
    "combiner.query": ["keyword", "vector"],
    "combiner.algorithm": "rrf",
    "shards.rows": 20
  }
}
```

**Key features**:

- One Solr round-trip; fusion is global across shards
- RRF formula: `score = sum(1 / (k + rank))`, k = `combiner.rrf.k` (Solr default 60)
- `shards.rows = topK * 2` over-fetches candidates per leg (per-leg depth is
  `max(start + rows, shards.rows)`)
- `filter` applies to both legs
- `minScore` turns the vector leg into `{!vectorSimilarity minReturn=minScore}`; it never touches the
  keyword leg or the fused output
- Schema-agnostic using `_text_` catch-all field
- Fallback: if the combined request fails (e.g. a collection without the `/hybrid` handler → 404) or the
  query cannot be embedded, it degrades to keyword-only, then vector-only
- **No `score` on hybrid rows.** Solr 10.1 orders hits by fused RRF score, but the per-document
  `score` it returns is a raw per-leg score (BM25 or cosine), so it is not requested. Use
  `buildHybridRequest(...)` with `debug=results` to see `combinerExplanations` (each document's rank
  per leg; note that 10.1 can attach the wrong leg *names* to those ranks)
- Grouping and `cursorMark` are not supported in combined mode
- Tests on `_default` collections register the handler with
  `SolrTestContainers.registerHybridHandler(...)`; `SolrHybridSearchIT` covers fusion end to end
- Testcontainers' `SolrContainer` 2.0.5 cannot start Solr 10 (bare `-DzkRun`); use
  `SolrTestContainers.newSolrContainer()`

## Observation and Metrics
The `SolrVectorStore` extends `AbstractObservationVectorStore` for integration with Micrometer:
- Tracks add/delete operations
- Monitors similarity search performance
- Provides metrics for observability platforms

## Filter Expression Handling

The application uses two different approaches for filter handling depending on the layer:

### SearchService Layer (Semantic Search via SearchRepository)

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

### SolrVectorStore Layer (Direct VectorStore API)

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

### Summary

- **SearchService → SearchRepository**: Solr-native syntax (full Solr capabilities)
- **Direct VectorStore API**: Spring AI syntax (simple equality only)
- **Why dual approach**: SearchService needs advanced Solr filters that Spring AI parser cannot handle
