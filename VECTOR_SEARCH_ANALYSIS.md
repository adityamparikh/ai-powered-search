# Vector Store, Indexing, and Semantic Search Implementation Analysis

## Executive Summary

This AI-powered search application is built on Spring Boot 3.5.4 with Java 21, integrating Apache Solr 9.9.0 for vector-based semantic search. The architecture combines:

1. **OpenAI Embeddings**: For generating 1536-dimensional vector embeddings (text-embedding-3-small model)
2. **Custom SolrVectorStore**: Spring AI-compatible vector store implementation for Solr
3. **Anthropic Claude**: For intelligent query parsing and natural language understanding
4. **PostgreSQL Chat Memory**: For maintaining conversational context
5. **Semantic and Keyword Search**: Dual search capabilities via REST APIs

---

## 1. Vector Store Implementation (SolrVectorStore)

### Location
`/Users/adityaparikh/IdeaProjects/ai-powered-search/src/main/java/dev/aparikh/aipoweredsearch/solr/vectorstore/SolrVectorStore.java`

### Architecture Overview

**Class Hierarchy:**
```
AbstractObservationVectorStore (Spring AI base)
  └─ SolrVectorStore (Custom implementation)
```

### Key Components

#### 1.1 Core Class: `SolrVectorStore`

**Extends:** `AbstractObservationVectorStore`

**Key Fields:**
- `SolrClient solrClient`: SolrJ client for Solr communication
- `String collection`: Target Solr collection name
- `SolrVectorStoreOptions options`: Configuration for field names and dimensions
- `boolean initializeSchema`: Flag to auto-initialize Solr schema
- `EmbeddingModel embeddingModel`: OpenAI embedding model instance

**Constructor:**
```java
protected SolrVectorStore(Builder builder)
```
- Validates SolrClient and collection name
- Initializes options (defaults to SolrVectorStoreOptions.defaults())
- Optionally calls `initializeSchema()` if enabled

**Builder Pattern:**
```java
public static Builder builder(SolrClient solrClient, String collection, EmbeddingModel embeddingModel)
```

#### 1.2 Configuration Class: `SolrVectorStoreOptions`

**Type:** Java Record (Immutable)

**Fields:**
```java
public record SolrVectorStoreOptions(
    String idFieldName,           // default: "id"
    String contentFieldName,      // default: "content"
    String vectorFieldName,       // default: "vector"
    String metadataPrefix,        // default: "metadata_"
    int vectorDimension           // default: 1536
)
```

**Default Values:**
- `DEFAULT_ID_FIELD = "id"`
- `DEFAULT_CONTENT_FIELD = "content"`
- `DEFAULT_VECTOR_FIELD = "vector"`
- `DEFAULT_METADATA_PREFIX = "metadata_"`
- `DEFAULT_VECTOR_DIMENSION = 1536`

**Builder Support:**
```java
SolrVectorStoreOptions.builder()
    .idFieldName("custom_id")
    .contentFieldName("custom_content")
    .vectorFieldName("custom_vector")
    .metadataPrefix("custom_meta_")
    .vectorDimension(1536)
    .build()
```

### 1.3 Core Methods

#### Document Indexing: `doAdd(List<Document> documents)`

**Flow:**
1. **Separation**: Splits documents into those with/without pre-existing embeddings
2. **Batch Embedding**: Generates embeddings for documents without them
   - Uses `embeddingModel.embedForResponse(List<String> texts)`
   - Validates embedding count matches document count
   - Stores embedding as `float[]` in document metadata
3. **Solr Conversion**: Converts Spring AI Documents to SolrInputDocument
4. **Persistence**: 
   - Adds documents to Solr via `solrClient.add(collection, solrDocs)`
   - Commits via `solrClient.commit(collection)`
5. **Logging**: Logs successful indexing with status

**Key Code:**
```java
@Override
public void doAdd(List<Document> documents) {
    // Separate documents without embeddings
    List<Document> documentsWithoutEmbeddings = documents.stream()
        .filter(doc -> {
            Object embedding = doc.getMetadata().get("embedding");
            return embedding == null || !(embedding instanceof float[]) || ((float[]) embedding).length == 0;
        })
        .collect(Collectors.toList());
    
    // Generate embeddings in batch
    if (!documentsWithoutEmbeddings.isEmpty()) {
        List<String> texts = documentsWithoutEmbeddings.stream()
            .map(Document::getText)
            .collect(Collectors.toList());
        EmbeddingResponse embeddingResponse = this.embeddingModel.embedForResponse(texts);
        // Add embeddings to documents
        for (int i = 0; i < documentsWithoutEmbeddings.size(); i++) {
            Document doc = documentsWithoutEmbeddings.get(i);
            float[] embedding = embeddingResponse.getResults().get(i).getOutput();
            doc.getMetadata().put("embedding", embedding);
        }
    }
    
    // Convert and persist
    List<SolrInputDocument> solrDocs = documents.stream()
        .map(this::toSolrDocument)
        .collect(Collectors.toList());
    solrClient.add(collection, solrDocs);
    solrClient.commit(collection);
}
```

#### Similarity Search: `doSimilaritySearch(SearchRequest request)`

**Flow:**
1. **Query Validation**: Ensures query string is not null/empty
2. **Query Embedding**: 
   - Converts query text to vector via `embeddingModel.embedForResponse(List<String>)`
   - Extracts `float[]` embedding from response
3. **KNN Query Building**:
   - Converts float array to string format: `[0.1, 0.2, 0.3, ...]`
   - Creates Solr KNN query: `{!knn f=vector topK=10}[...]`
4. **Filter Application**:
   - Converts Spring AI filter expressions to Solr syntax
   - Adds filter queries via `query.addFilterQuery(solrFilter)`
5. **Field Selection**:
   - Returns: id, content, vector field, score pseudo-field, metadata_* fields
6. **Query Execution**:
   - Uses POST method to avoid URI-too-long errors with large vectors
   - Calls `solrClient.query(collection, query, SolrRequest.METHOD.POST)`
7. **Post-Query Filtering**:
   - Filters results by similarity threshold if specified (score >= threshold)
   - Threshold filtering done post-query (score is pseudo-field only available after query)

**KNN Query Format:**
```
{!knn f=vector_field_name topK=10}[embedding_values]
```

**Filter Expression Conversion:**
- Converts Spring AI expressions like `category == 'AI'` to Solr format `metadata_category:AI`
- Uses reflection to extract expression type, key, and value
- Fallback parsing from string representation
- Adds metadata prefix if not already present

**Key Code (Simplified):**
```java
@Override
public List<Document> doSimilaritySearch(SearchRequest request) {
    // Generate embedding for query
    EmbeddingResponse embeddingResponse = this.embeddingModel.embedForResponse(
        Collections.singletonList(request.getQuery()));
    float[] queryEmbedding = embeddingResponse.getResults().get(0).getOutput();
    
    // Build KNN query
    String vectorString = floatArrayToString(queryEmbedding);
    String knnQuery = String.format("{!knn f=%s topK=%d}%s",
        options.vectorFieldName(), request.getTopK(), vectorString);
    
    SolrQuery query = new SolrQuery(knnQuery);
    
    // Apply filters
    if (request.getFilterExpression() != null) {
        String solrFilter = convertFilterToSolrQuery(request.getFilterExpression());
        if (solrFilter != null && !solrFilter.isEmpty()) {
            query.addFilterQuery(solrFilter);
        }
    }
    
    // Execute and filter by threshold
    QueryResponse response = solrClient.query(collection, query, SolrRequest.METHOD.POST);
    return response.getResults().stream()
        .map(solrDoc -> toDocument(solrDoc, threshold))
        .filter(Objects::nonNull)
        .filter(doc -> {
            if (threshold >= 0) {
                Object scoreObj = doc.getMetadata().get("score");
                if (scoreObj instanceof Number) {
                    double score = ((Number) scoreObj).doubleValue();
                    return score >= threshold;
                }
                return false;
            }
            return true;
        })
        .collect(Collectors.toList());
}
```

#### Document Deletion: `doDelete(List<String> idList)`

**Flow:**
1. Deletes documents by ID from Solr
2. Commits changes
3. Logs deletion status

```java
@Override
public void doDelete(List<String> idList) {
    UpdateResponse response = solrClient.deleteById(collection, idList);
    solrClient.commit(collection);
    log.debug("Deleted {} documents from Solr collection '{}', status: {}",
        idList.size(), collection, response.getStatus());
}
```

#### Document-to-Solr Conversion: `toSolrDocument(Document document)`

**Conversion Logic:**
1. **ID Field**: Uses provided ID or generates UUID
2. **Content Field**: Stores document text
3. **Vector Field**: 
   - Extracts `float[]` embedding from metadata
   - Converts to `List<Float>` for Solr storage
4. **Metadata Fields**: 
   - Prefixes with `metadata_` (configurable)
   - Skips embedding field (stored separately)
   - Skips id, content, vector fields (stored separately)

```java
private SolrInputDocument toSolrDocument(Document document) {
    SolrInputDocument solrDoc = new SolrInputDocument();
    
    // Set ID
    String id = document.getId() != null ? document.getId() : UUID.randomUUID().toString();
    solrDoc.addField(options.idFieldName(), id);
    
    // Set content
    solrDoc.addField(options.contentFieldName(), document.getText());
    
    // Set vector embedding
    Object embeddingObj = document.getMetadata().get("embedding");
    if (embeddingObj instanceof float[]) {
        float[] embedding = (float[]) embeddingObj;
        List<Float> embeddingList = new ArrayList<>();
        for (float val : embedding) {
            embeddingList.add(val);
        }
        solrDoc.addField(options.vectorFieldName(), embeddingList);
    }
    
    // Add metadata fields
    if (document.getMetadata() != null) {
        document.getMetadata().forEach((key, value) -> {
            if (!options.idFieldName().equals(key) &&
                !options.contentFieldName().equals(key) &&
                !options.vectorFieldName().equals(key) &&
                !"embedding".equals(key)) {
                solrDoc.addField(options.metadataPrefix() + key, value);
            }
        });
    }
    
    return solrDoc;
}
```

#### Solr-to-Document Conversion: `toDocument(SolrDocument solrDoc, double similarityThreshold)`

**Conversion Logic:**
1. **ID Extraction**: Handles both single and multi-valued ID fields
2. **Content Extraction**: Extracts content, handling list values
3. **Score Extraction**: Gets cosine similarity score, applies threshold filtering
4. **Vector Reconstruction**: Converts List<Number> back to float[]
5. **Metadata Extraction**:
   - Extracts fields prefixed with `metadata_`
   - Handles multi-valued fields (takes first value)
   - Type conversion for specific fields (e.g., Long to Integer for year)
6. **Document Building**: Constructs Spring AI Document with all components

**Similarity Threshold:**
- Cosine similarity typically in [0, 1] range (1 = most similar)
- Applied as post-query filter
- Null thresholds mean no filtering

---

## 2. Embedding Model Configuration

### Location
`/Users/adityaparikh/IdeaProjects/ai-powered-search/src/main/java/dev/aparikh/aipoweredsearch/config/SpringAiConfig.java`

### Configuration Details

**Embedding Model:**
- **Provider**: OpenAI
- **Model**: `text-embedding-3-small`
- **Dimensions**: 1536
- **Configuration**:
  ```properties
  spring.ai.openai.api-key=${OPENAI_API_KEY}
  spring.ai.openai.embedding.options.model=text-embedding-3-small
  spring.ai.openai.embedding.options.dimensions=1536
  ```

**Bean Definition:**
```java
@Bean
@ConditionalOnMissingBean(EmbeddingModel.class)
public EmbeddingModel embeddingModel(@Value("${spring.ai.openai.api-key}") String apiKey) {
    OpenAiApi openAiApi = OpenAiApi.builder()
            .apiKey(apiKey)
            .build();
    return new OpenAiEmbeddingModel(openAiApi);
}
```

**Known Issues:**
- Jetty 12.x HTTP authentication challenge error with invalid API keys
- Workaround: Ensure valid OPENAI_API_KEY environment variable
- Tests skip if OPENAI_API_KEY not set via `@EnabledIfEnvironmentVariable`

---

## 3. Solr Vector Field Schema Requirements

### Required Field Definitions

```xml
<!-- Basic fields -->
<field name="id" type="string" indexed="true" stored="true" required="true"/>
<field name="content" type="text_general" indexed="true" stored="true"/>
<field name="vector" type="knn_vector_1536" indexed="true" stored="true"/>

<!-- Field type for vector search -->
<fieldType name="knn_vector_1536" 
           class="solr.DenseVectorField"
           vectorDimension="1536" 
           similarityFunction="cosine"/>
```

### Field Configuration Details

**Vector Field Type:**
- **Class**: `solr.DenseVectorField`
- **Vector Dimension**: 1536 (matches OpenAI embedding dimension)
- **Similarity Function**: `cosine` (standard for text embeddings)
- **KNN Algorithm**: `hnsw` (Hierarchical Navigable Small World)

**Field Storage:**
- `indexed="true"` - Enable KNN indexing
- `stored="true"` - Return vectors in search results
- Multi-valued NOT supported for dense vectors

---

## 4. Indexing Flow

### 4.1 IndexController

**Location:** `/Users/adityaparikh/IdeaProjects/ai-powered-search/src/main/java/dev/aparikh/aipoweredsearch/indexing/IndexController.java`

**REST Endpoints:**
```
POST /api/v1/index/{collection}
POST /api/v1/index/{collection}/batch
```

#### Endpoint 1: Single Document Indexing
```java
@PostMapping("/{collection}")
public IndexResponse indexDocument(
    @PathVariable String collection,
    @RequestBody IndexRequest indexRequest)
```

**Request Model: `IndexRequest`**
```java
public record IndexRequest(
    String id,                          // Optional, auto-generated if null
    String content,                     // Required: text to be embedded and indexed
    Map<String, Object> metadata        // Optional: additional metadata
)
```

**Response Model: `IndexResponse`**
```java
public record IndexResponse(
    int indexed,                        // Number successfully indexed
    int failed,                         // Number of failures
    List<String> documentIds,           // IDs of indexed documents
    String message                      // Status message
)
```

#### Endpoint 2: Batch Document Indexing
```java
@PostMapping("/{collection}/batch")
public IndexResponse indexDocuments(
    @PathVariable String collection,
    @RequestBody BatchIndexRequest batchRequest)
```

**Request Model: `BatchIndexRequest`**
```java
public record BatchIndexRequest(
    List<IndexRequest> documents    // List of documents to index
)
```

### 4.2 IndexService

**Location:** `/Users/adityaparikh/IdeaProjects/ai-powered-search/src/main/java/dev/aparikh/aipoweredsearch/indexing/IndexService.java`

**Dependencies Injected:**
- `EmbeddingModel embeddingModel` - OpenAI embedding model
- `SolrClient solrClient` - Solr client for persistence

**Method 1: Single Document Indexing**
```java
public IndexResponse indexDocument(String collection, IndexRequest indexRequest)
```

**Flow:**
1. Generate document ID (UUID if not provided)
2. Create metadata map from request
3. Build Spring AI Document:
   ```java
   Document document = Document.builder()
       .id(docId)
       .text(indexRequest.content())
       .metadata(metadata)
       .build();
   ```
4. Create SolrVectorStore instance
5. Add document (embeddings generated automatically by VectorStore)
6. Return IndexResponse with success status
7. Handle exceptions and return error response

**Method 2: Batch Document Indexing**
```java
public IndexResponse indexDocuments(String collection, BatchIndexRequest batchRequest)
```

**Flow:**
1. Process each document in batch:
   - Generate ID if needed
   - Create metadata map
   - Build Spring AI Document
   - Add to documents list
2. Create SolrVectorStore instance
3. Batch add all documents (embeddings generated in batch by VectorStore for efficiency)
4. Return response with indexed count, failed count, and document IDs
5. Handle partial failures gracefully

**Advantages of Batch Processing:**
- Single API call to OpenAI for all embeddings
- Better performance for large document sets
- Single Solr commit for all documents

---

## 5. Semantic Search Flow

### 5.1 SearchController

**Location:** `/Users/adityaparikh/IdeaProjects/ai-powered-search/src/main/java/dev/aparikh/aipoweredsearch/search/SearchController.java`

**REST Endpoints:**
```
GET /api/v1/search/{collection}?query=...
GET /api/v1/search/{collection}/semantic?query=...
```

#### Endpoint 1: Keyword Search
```java
@GetMapping("/{collection}")
public SearchResponse search(
    @PathVariable String collection,
    @RequestParam("query") String query)
```

#### Endpoint 2: Semantic Search (Vector Similarity)
```java
@GetMapping("/{collection}/semantic")
public SearchResponse semanticSearch(
    @PathVariable String collection,
    @RequestParam("query") String query)
```

**Response Model: `SearchResponse`**
```java
public record SearchResponse(
    List<Map<String, Object>> documents,      // Result documents
    Map<String, List<FacetCount>> facetCounts // Facet results
) {
    public record FacetCount(String value, long count) {}
}
```

### 5.2 SearchService

**Location:** `/Users/adityaparikh/IdeaProjects/ai-powered-search/src/main/java/dev/aparikh/aipoweredsearch/search/SearchService.java`

**Dependencies:**
- `Resource systemResource` - Keyword search system prompt
- `Resource semanticSystemResource` - Semantic search system prompt
- `SearchRepository searchRepository` - Solr query execution
- `ChatClient chatClient` - Anthropic Claude AI (with chat memory)
- `EmbeddingModel embeddingModel` - OpenAI embeddings
- `SolrClient solrClient` - Direct Solr access

#### Method 1: Keyword Search
```java
public SearchResponse search(String collection, String freeTextQuery)
```

**Flow:**
1. **Schema Retrieval**:
   - Get field schema from Solr via SearchRepository
   - Returns list of FieldInfo objects with field types and attributes

2. **Claude AI Query Generation**:
   - Load system prompt from `classpath:/prompts/system-message.st`
   - Build user message with query and field information
   - Use conversation ID "007" for chat memory
   - Call Claude to generate structured Solr query
   ```java
   QueryGenerationResponse queryGenerationResponse = chatClient.prompt()
       .system(systemResource)
       .user(userMessage)
       .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
       .call()
       .entity(QueryGenerationResponse.class);
   ```

3. **Query Response Parsing**:
   - Claude returns JSON with Solr query parameters:
   ```java
   public record QueryGenerationResponse(
       String q,                          // Main query
       List<String> fq,                   // Filter queries
       String sort,                       // Sort parameter
       String fl,                         // Field list
       @JsonProperty("facet.fields") List<String> facetFields,
       @JsonProperty("facet.query") String facetQuery
   )
   ```

4. **Search Execution**:
   - Build SearchRequest from Claude response
   - Execute via SearchRepository.search()
   - Return SearchResponse with documents and facets

#### Method 2: Semantic Search (Vector Similarity)
```java
public SearchResponse semanticSearch(String collection, String freeTextQuery,
                                     Integer k, Double minScore, String fieldsCsv)
```

**Flow:**

1. **Parameter Validation**:
    - Validates collection and query inputs
    - Sets topK to k (default: 50 for better recall)
    - Sets similarityThreshold to minScore (default: 0.0)

2. **Vector Store Search**:
    - Gets VectorStore instance for collection via VectorStoreFactory
    - Build Spring AI SearchRequest:
     ```java
     org.springframework.ai.vectorstore.SearchRequest searchRequest =
         org.springframework.ai.vectorstore.SearchRequest.builder()
             .query(freeTextQuery)                // Query text to embed
             .topK(topK)                         // Return topK nearest neighbors
             .similarityThreshold(similarityThreshold) // Min similarity score
             .build();
     ```
   - Call `vectorStore.similaritySearch(searchRequest)`
    - VectorStore (SolrVectorStore) automatically:
        - Generates 1536-dim embedding using OpenAI text-embedding-3-small
        - Executes KNN query in Solr using HNSW algorithm
     - Returns similar documents ranked by cosine similarity

3. **Response Conversion**:
   - Convert Spring AI Documents to SearchResponse format
   - Include only requested fields (if fieldsCsv provided)
   - Include id, content, metadata, and similarity score
   - Note: No Claude AI involvement - pure vector similarity search
   - Note: No filter generation - maximizes recall for semantic search

#### Method 3: Hybrid Search (Client-Side RRF)

```java
public SearchResponse hybridSearch(String collection, String freeTextQuery,
                                   Integer k, Double minScore, String fieldsCsv)
```

**Flow:**

1. **Parameter Validation**:
    - Validates collection and query inputs
    - Sets topK to k (default: 100)
    - Uses minScore for filtering results (optional)

2. **Claude AI Filter Generation**:
    - Uses semantic search system prompt
    - Generates filter queries for structured filtering
    - Returns filters, sort, field list

3. **Hybrid Search Execution**:
    - Delegates to `SearchRepository.executeHybridRerankSearch()`
    - Executes two searches in parallel:
        - **Keyword search**: edismax with `_text_` catch-all field
        - **Vector search**: KNN with OpenAI embeddings
    - Fetches `topK * 2` results from each for better fusion

4. **Client-Side RRF Merging**:
    - Uses `RrfMerger` class with formula: `score = sum(1 / (k + rank))`
    - Default k=60 for balanced fusion
    - Documents in both result sets get combined scores
    - Re-ranks all results by RRF score

5. **Fallback Strategy**:
    - If hybrid returns no results → fallback to keyword-only
    - If keyword returns no results → fallback to vector-only
    - Ensures robust search even with poor queries

**Conversation Memory:**
- Uses ChatMemory with conversation ID "007"
- Maintains context across multiple search requests
- Stored in PostgreSQL via JDBC

### 5.3 SearchRepository

**Location:** `/Users/adityaparikh/IdeaProjects/ai-powered-search/src/main/java/dev/aparikh/aipoweredsearch/search/SearchRepository.java`

**Method 1: Keyword Search Execution**
```java
public SearchResponse search(String collection, SearchRequest searchRequest)
```

**Implementation:**
1. Build SolrQuery from SearchRequest parameters
2. Add filter queries via `query.addFilterQuery(fq)`
3. Set sort parameter if present
4. Set field list if present
5. Configure faceting if requested
6. Execute via `solrClient.query(collection, query)`
7. Convert results to SearchResponse with facet counts

**Method 2: Hybrid Search with RRF**
```java
public SearchResponse executeHybridRerankSearch(String collection, String query,
                                                int topK, String filterExpression,
                                                String fieldsCsv, Double minScore)
```

**Implementation:**

1. Execute keyword search via `executeKeywordSearch()`:
    - Uses edismax query parser with `_text_` catch-all field
    - Fetches `topK * 2` results for better fusion
    - Applies filter expression if provided

2. Execute vector search via `executeVectorSearch()`:
    - Generates embedding using EmbeddingService
    - Builds KNN query: `{!knn f=vector topK=...}[vector]`
    - Fetches `topK * 2` results
    - Uses POST method to avoid URI length issues

3. Merge results using RrfMerger:
   ```java
   RrfMerger rrfMerger = new RrfMerger(); // k=60 default
   List<Map<String, Object>> mergedResults = rrfMerger.merge(
       keywordResults, vectorResults);
   ```

4. Apply minScore filtering and topK limiting
5. Implement fallback strategy if no results

**Method 3: Field Schema Retrieval**
```java
public List<FieldInfo> getFieldsWithSchema(String collection)
```

**Implementation:**
1. Get all fields from Solr schema via SchemaRequest
2. Analyze actual usage in documents (sample 100 docs)
3. Filter to only actually-used fields
4. Skip internal Solr fields (starting with "_")
5. Return FieldInfo objects with:
   ```java
   public record FieldInfo(
       String name,
       String type,
       boolean multiValued,
       boolean stored,
       boolean docValues,
       boolean indexed
   )
   ```

---

## 6. System Prompts

### 6.1 Keyword Search System Prompt
**Location:** `/Users/adityaparikh/IdeaProjects/ai-powered-search/src/main/resources/prompts/system-message.st`

**Purpose:** Guide Claude in converting free-text queries to Solr search parameters

**Key Sections:**
1. **Parameter Definitions**:
   - `q` - Main search query (Solr syntax)
   - `fq` - Filter queries (performance optimization)
   - `sort` - Result ordering
   - `fl` - Field list to return
   - `facet.fields` - Field-value faceting
   - `facet.query` - Range/expression faceting

2. **Field Type Guidelines**:
   - **text**: Full-text search (tokenization, stemming)
   - **string**: Exact match, faceting, sorting
   - **numeric**: Range queries, numeric sorting
   - **date**: Date range queries, temporal filtering
   - **boolean**: Binary flags
   - **location**: Geospatial searches

3. **Guidance**:
   - Analyze user intent from free-text query
   - Choose appropriate fields and Solr syntax
   - Use fq for performance optimization
   - Select relevant fields to return
   - Consider faceting for exploration

### 6.2 Semantic Search System Prompt
**Location:** `/Users/adityaparikh/IdeaProjects/ai-powered-search/src/main/resources/prompts/semantic-search-system-message.st`

**Purpose:** Guide Claude in generating filters and field selections for semantic search

**Key Sections:**
1. **Focus Areas** (not handling main query):
   - Filter query generation for refinement
   - Sort specification (defaults to similarity)
   - Field selection
   - Facet suggestions for exploration

2. **Semantic Search Context**:
   - Explains that vector similarity handles semantic meaning
   - Guides filtering to constrain search space
   - Emphasizes faceting importance for exploration
   - Provides semantic-specific filter examples

3. **Filter Guidelines**:
   - Category/type filtering
   - Date range filtering
   - Status/flag filtering
   - Range-based filtering
   - Exclusion patterns

4. **When to Apply Filters**:
   - User specifies categories
   - User specifies time constraints
   - User specifies exclusions
   - User specifies status conditions
   - User specifies value ranges

---

## 7. Configuration Details

### 7.1 Application Properties

**File:** `/Users/adityaparikh/IdeaProjects/ai-powered-search/src/main/resources/application.properties`

```properties
# Application
spring.application.name=ai-powered-search
spring.threads.virtual.enabled=true

# Solr Configuration
solr.url=http://localhost:8983/solr

# Spring AI - Anthropic (Chat)
spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}
spring.ai.anthropic.chat.options.model=claude-sonnet-4-5

# Spring AI - OpenAI (Embeddings)
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.embedding.options.model=text-embedding-3-small
spring.ai.openai.embedding.options.dimensions=1536

# PostgreSQL (Chat Memory)
spring.datasource.url=jdbc:postgresql://localhost:5432/chatmemory
spring.datasource.username=${POSTGRES_USER:postgres}
spring.datasource.password=${POSTGRES_PASSWORD:postgres}
spring.datasource.driver-class-name=org.postgresql.Driver

# Chat Memory
spring.ai.chat.memory.repository.jdbc.initialize-schema=always
spring.ai.chat.memory.repository.type=jdbc
spring.main.allow-bean-definition-overriding=true

# OpenAPI / Swagger
springdoc.swagger-ui.path=/swagger-ui.html
springdoc.api-docs.path=/api-docs
springdoc.swagger-ui.operationsSorter=method
springdoc.swagger-ui.tagsSorter=alpha

# Logging
logging.level.org.springframework.ai.chat.client.advisor=debug
logging.level.dev.aparikh.aipoweredsearch=debug
```

### 7.2 Solr Configuration

**File:** `/Users/adityaparikh/IdeaProjects/ai-powered-search/src/main/java/dev/aparikh/aipoweredsearch/config/SolrConfig.java`

**Class:** `SolrConfig`

**Bean Creation:**
```java
@Bean
SolrClient solrClient(SolrConfigurationProperties properties)
```

**URL Normalization:**
- Ensures URL ends with "/"
- Appends "/solr/" if not present
- Handles various input formats

**Connection Configuration:**
- Connection Timeout: 10,000ms
- Socket Timeout: 60,000ms
- Client Type: HttpSolrClient (HTTP/1.1)

**Supported URL Formats:**
- `http://localhost:8983` → `http://localhost:8983/solr/`
- `http://localhost:8983/` → `http://localhost:8983/solr/`
- `http://localhost:8983/solr` → `http://localhost:8983/solr/`

### 7.3 Spring AI Configuration

**File:** `/Users/adityaparikh/IdeaProjects/ai-powered-search/src/main/java/dev/aparikh/aipoweredsearch/config/SpringAiConfig.java`

**Beans:**
1. **EmbeddingModel**: OpenAI embeddings (text-embedding-3-small)
2. **ChatClient**: Anthropic Claude chat with advisors

**Advisors:**
- `MessageChatMemoryAdvisor`: Maintains conversation history
- `SimpleLoggerAdvisor`: Logs chat interactions

---

## 8. Key Classes and Models

### 8.1 Model Classes

**SearchRequest.java**
```java
public record SearchRequest(
    String query,                   // Main Solr query
    List<String> filterQueries,    // Filter queries
    String sort,                    // Sort parameter
    String fieldList,               // Comma-separated field list
    Facet facet                     // Faceting configuration
) {
    public record Facet(List<String> fields, String query) {}
    public boolean hasFacets() {}
    public boolean hasSort() {}
    public boolean hasFieldList() {}
}
```

**QueryGenerationResponse.java**
```java
public record QueryGenerationResponse(
    String q,
    List<String> fq,
    String sort,
    String fl,
    @JsonProperty("facet.fields") List<String> facetFields,
    @JsonProperty("facet.query") String facetQuery
)
```

**FieldInfo.java**
```java
public record FieldInfo(
    String name,
    String type,
    boolean multiValued,
    boolean stored,
    boolean docValues,
    boolean indexed
)
```

### 8.2 Building Spring AI Document

```java
Document document = Document.builder()
    .id(docId)              // Unique identifier
    .text(content)          // Text content to embed
    .metadata(metadata)     // Additional metadata
    .build();
```

**Spring AI Embedding:** Automatically handled by SolrVectorStore during `add()` call

---

## 9. Dependency Versions

**Build Tool:** Gradle 8.x with Kotlin DSL

**Key Dependencies:**
- Spring Boot: 3.5.7
- Spring AI: 1.1.0-M4
- Java: 21
- Apache Solr SolrJ: 9.9.0
- OpenAI API (Spring AI integration)
- Anthropic API (Spring AI integration)
- PostgreSQL Driver: latest
- TestContainers for integration tests

---

## 10. Data Flow Diagrams

### 10.1 Indexing Flow

```
HTTP POST /api/v1/index/{collection}
    ↓
IndexController.indexDocument()
    ↓
IndexService.indexDocument()
    ↓
Create Spring AI Document (with id, content, metadata)
    ↓
SolrVectorStore.add([document])
    ├─ Check if embedding exists in metadata
    ├─ If not: Call EmbeddingModel.embedForResponse([content])
    │   └─ OpenAI: text-embedding-3-small → 1536-dim vector
    ├─ Store embedding in document.metadata["embedding"]
    └─ Convert to SolrInputDocument
        └─ Add fields: id, content, vector, metadata_*
    ↓
solrClient.add(collection, solrDocs)
    ↓
solrClient.commit(collection)
    ↓
Return IndexResponse (indexed count, document IDs)
```

### 10.2 Semantic Search Flow

```
HTTP GET /api/v1/search/{collection}/semantic?query=...
    ↓
SearchController.semanticSearch()
    ↓
SearchService.semanticSearch()
    ├─ SearchRepository.getFieldsWithSchema(collection)
    │   └─ Returns: FieldInfo[] (name, type, indexed, stored, etc.)
    ├─ Build user message: query + field information
    ├─ Call ChatClient with semanticSystemResource prompt
    │   └─ Anthropic Claude (claude-sonnet-4-5)
    │       └─ Generates: filters (fq), fields (fl), facets, sort
    ├─ Create SolrVectorStore(solrClient, collection, embeddingModel)
    ├─ Build SearchRequest:
    │   ├─ query: freeTextQuery (to be embedded)
    │   ├─ topK: 10 (default)
    │   └─ filterExpression: Claude-generated filters
    └─ Call vectorStore.similaritySearch(searchRequest)
        ├─ Generate embedding: EmbeddingModel.embedForResponse([query])
        │   └─ OpenAI: text-embedding-3-small → 1536-dim vector
        ├─ Build Solr KNN query: {!knn f=vector topK=10}[embedding]
        ├─ Add filter queries (if present)
        ├─ Execute: solrClient.query(collection, query, METHOD.POST)
        │   └─ Returns: Results ranked by cosine similarity (0-1 score)
        ├─ Convert SolrDocuments → Spring AI Documents
        └─ Apply similarity threshold filter
    ↓
Return SearchResponse (documents with metadata, facets)
```

### 10.3 Keyword Search Flow

```
HTTP GET /api/v1/search/{collection}?query=...
    ↓
SearchController.search()
    ↓
SearchService.search()
    ├─ SearchRepository.getFieldsWithSchema(collection)
    ├─ Build user message: query + field information
    ├─ Call ChatClient with systemResource prompt
    │   └─ Anthropic Claude
    │       └─ Generates: q, fq, sort, fl, facets (Solr syntax)
    └─ Call SearchRepository.search(collection, searchRequest)
        ├─ Build SolrQuery(q)
        ├─ Add filter queries: query.addFilterQuery(fq)
        ├─ Set sort, field list, faceting parameters
        ├─ Execute: solrClient.query(collection, query)
        └─ Return: Documents + facet counts
    ↓
Return SearchResponse
```

---

## 11. Key Implementation Notes

### 11.1 Embedding Generation

**When Generated:**
- During document indexing via `SolrVectorStore.doAdd()`
- During search via `SolrVectorStore.doSimilaritySearch()`
- Batch generation for efficiency (single API call for multiple documents)

**Embedding Model:**
- OpenAI text-embedding-3-small
- 1536 dimensions
- Cosine similarity metric (standard for text)

### 11.2 Vector Storage in Solr

**Field Type:** `solr.DenseVectorField`
- Optimized for KNN search
- Uses HNSW (Hierarchical Navigable Small World) algorithm
- Single vector per document (not multi-valued)

**Query Syntax:** `{!knn f=fieldname topK=N}[v1, v2, v3, ...]`
- KNN (K-Nearest Neighbors) parser
- Returns top N documents by vector similarity
- Cosine similarity score returned in pseudo-field "score"

### 11.3 Filter Expression Conversion

**Input:** Spring AI Filter.Expression
- Example: `category == 'AI'`

**Output:** Solr query syntax
- Example: `metadata_category:AI`

**Conversion Method:**
1. Try reflection-based extraction of expression type, key, value
2. Fallback to string parsing from toString() representation
3. Add metadata prefix if not present
4. Return Solr field:value format

### 11.4 Post-Query Filtering

Similarity threshold filtering occurs AFTER query execution because:
- `score` is a pseudo-field only available in results
- Cannot be used in filter query expressions
- Must manually filter returned documents by score value

### 11.5 Chat Memory Integration

**Conversation ID:** "007" (hardcoded)
- Used for both keyword and semantic search
- Maintains context across multiple requests
- Stored in PostgreSQL JDBC repository

**Advisors:**
- MessageChatMemoryAdvisor: Automatic history management
- SimpleLoggerAdvisor: Debug logging

---

## 12. API Documentation

### Swagger/OpenAPI

**Endpoints Documented:**
- `POST /api/v1/index/{collection}` - Index single document
- `POST /api/v1/index/{collection}/batch` - Batch index documents
- `GET /api/v1/search/{collection}` - Keyword search
- `GET /api/v1/search/{collection}/semantic` - Semantic search

**Access:**
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/api-docs`

---

## 13. Summary Table

| Component | Technology | Key Details |
|-----------|-----------|-------------|
| **Vector Store** | Custom SolrVectorStore | Spring AI compatible, KNN search, batch embedding |
| **Embeddings** | OpenAI text-embedding-3-small | 1536 dimensions, cosine similarity |
| **Chat/Query Generation** | Anthropic Claude | claude-sonnet-4-5, chat memory with conversation ID "007" |
| **Search Engine** | Apache Solr | 9.9.0, DenseVectorField, KNN queries |
| **Chat Memory** | PostgreSQL | JDBC repository, auto-schema initialization |
| **HTTP Client** | HttpSolrClient | HTTP/1.1, 10s connect timeout, 60s socket timeout |
| **Framework** | Spring Boot | 3.5.7, Java 21, virtual threads enabled |
| **Build Tool** | Gradle | Kotlin DSL, Spring dependency management |

