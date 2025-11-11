# Vector Search Implementation - Quick Reference

## Core Components at a Glance

### 1. Vector Store: SolrVectorStore
**File:** `solr/vectorstore/SolrVectorStore.java`
- Custom Spring AI VectorStore implementation for Solr
- Handles embedding generation and persistence
- Methods: `doAdd()`, `doDelete()`, `doSimilaritySearch()`
- Options configurable via `SolrVectorStoreOptions` record

### 2. Embedding Model
- **Provider:** OpenAI
- **Model:** text-embedding-3-small
- **Dimensions:** 1536
- **Configured in:** `config/SpringAiConfig.java`
- **Property:** `spring.ai.openai.embedding.options.dimensions=1536`

### 3. Search Model
- **Provider:** Anthropic
- **Model:** claude-sonnet-4-5
- **Purpose:** Query generation and filter extraction
- **Configured in:** `config/SpringAiConfig.java`

### 4. Search Engine
- **Technology:** Apache Solr 9.9.0
- **Field Type:** DenseVectorField
- **Algorithm:** HNSW (Hierarchical Navigable Small World)
- **Similarity:** Cosine (0-1 scale, 1 = identical)

---

## REST API Endpoints

### Indexing
```
POST   /api/v1/index/{collection}
       Body: { "id": "...", "content": "...", "metadata": {...} }
       Response: { "indexed": 1, "failed": 0, "documentIds": [...], "message": "..." }

POST   /api/v1/index/{collection}/batch
       Body: { "documents": [{ "id": "...", "content": "...", "metadata": {...} }, ...] }
       Response: { "indexed": N, "failed": M, "documentIds": [...], "message": "..." }
```

### Searching
```
GET    /api/v1/search/{collection}?query=...
       Response: { "documents": [{"id": "...", "content": "...", "score": ...}], "facetCounts": {...} }

GET    /api/v1/search/{collection}/semantic?query=...
       Response: { "documents": [{"id": "...", "content": "...", "score": ...}], "facetCounts": {...} }
```

---

## Keyword Search Flow (5 Steps)

```
1. Get Field Schema
   SearchRepository.getFieldsWithSchema(collection)
   └─ Returns: List<FieldInfo> with name, type, indexed, stored, etc.

2. Generate Solr Query
   ChatClient.prompt() with system-message.st
   │ Passes: query + field info
   └─ Claude generates: q, fq, sort, fl, facet.fields, facet.query

3. Execute Solr Query
   SolrClient.query(collection, SolrQuery)
   └─ Returns: documents + facet counts

4. Format Response
   Convert SolrDocument → SearchResponse

5. Return to Client
```

---

## Semantic Search Flow (5 Steps)

```
1. Get Field Schema
   SearchRepository.getFieldsWithSchema(collection)

2. Generate Filters
   ChatClient.prompt() with semantic-search-system-message.st
   │ Passes: query + field info
   └─ Claude generates: fq, sort, fl, facets (NO main q)

3. Build Vector Query
   SolrVectorStore.similaritySearch(SearchRequest)
   │ ├─ Generate embedding: EmbeddingModel.embedForResponse([query])
   │ ├─ Create KNN query: {!knn f=vector topK=10}[embedding]
   │ └─ Apply filters: query.addFilterQuery(fq)
   └─ Execute: SolrClient.query()

4. Filter by Similarity
   Filter results by score >= threshold
   └─ (Threshold filtering is post-query because score is pseudo-field)

5. Return to Client
```

---

## Key Classes & Methods

### SolrVectorStore
```java
// Add documents with auto-embedding
void add(List<Document> documents)

// Search by similarity
List<Document> similaritySearch(SearchRequest request)

// Delete documents
void delete(List<String> ids)
```

### SearchService
```java
// Keyword search with Claude query generation
SearchResponse search(String collection, String query)

// Vector similarity search with Claude filter generation
SearchResponse semanticSearch(String collection, String query)
```

### SearchRepository
```java
// Execute Solr keyword query
SearchResponse search(String collection, SearchRequest request)

// Get field schema and types
List<FieldInfo> getFieldsWithSchema(String collection)
```

### IndexService
```java
// Index single document (embeddings auto-generated)
IndexResponse indexDocument(String collection, IndexRequest request)

// Batch index documents (more efficient)
IndexResponse indexDocuments(String collection, BatchIndexRequest request)
```

---

## Configuration Quick Reference

### Environment Variables Required
```bash
ANTHROPIC_API_KEY=sk-ant-...       # For Claude queries
OPENAI_API_KEY=sk-...              # For embeddings
POSTGRES_USER=postgres             # Chat memory database
POSTGRES_PASSWORD=...              # Chat memory database
```

### Solr Collection Setup
```bash
# Create collection
solr create_collection -c {name} -d _default

# Add vector field type to schema
curl -X POST -H 'Content-Type: application/json' \
  --data '{
    "add-field-type": {
      "name": "knn_vector_1536",
      "class": "solr.DenseVectorField",
      "vectorDimension": 1536,
      "similarityFunction": "cosine"
    }
  }' \
  http://localhost:8983/solr/{collection}/schema

# Add vector field to collection
curl -X POST -H 'Content-Type: application/json' \
  --data '{
    "add-field": {
      "name": "vector",
      "type": "knn_vector_1536",
      "indexed": true,
      "stored": true
    }
  }' \
  http://localhost:8983/solr/{collection}/schema
```

### Application Properties
```properties
solr.url=http://localhost:8983
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.embedding.options.model=text-embedding-3-small
spring.ai.openai.embedding.options.dimensions=1536
spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}
spring.ai.anthropic.chat.options.model=claude-sonnet-4-5
spring.datasource.url=jdbc:postgresql://localhost:5432/chatmemory
```

---

## Important Implementation Details

### Embedding Generation
- **Automatic:** Happens in SolrVectorStore.doAdd() if not already present
- **Batch Processing:** Single API call for multiple documents
- **Storage:** Stored as float[] in document metadata, then as List<Float> in Solr

### Vector Search Query Format
```
{!knn f=vector_field_name topK=10}[0.123, 0.456, ...]
```
- Uses Solr's KNN parser
- POST method to avoid URI-too-long errors
- Cosine similarity (0-1 range) returned in "score" pseudo-field

### Filter Expression Conversion
```java
// Input (Spring AI)
category == 'AI'

// Output (Solr)
metadata_category:AI
```

### Conversation Memory
- **ID:** "007" (hardcoded, constant across all searches)
- **Storage:** PostgreSQL JDBC repository
- **Purpose:** Maintains context across multiple search requests
- **Advisors:** MessageChatMemoryAdvisor + SimpleLoggerAdvisor

### Similarity Threshold Filtering
- Applied AFTER query (post-query filtering)
- Reason: score is pseudo-field only available in results
- Filters documents where score >= threshold

---

## Field Configuration in Solr

### Required Fields
```xml
<field name="id" type="string" indexed="true" stored="true" required="true"/>
<field name="content" type="text_general" indexed="true" stored="true"/>
<field name="vector" type="knn_vector_1536" indexed="true" stored="true"/>
```

### Vector Field Type
```xml
<fieldType name="knn_vector_1536"
           class="solr.DenseVectorField"
           vectorDimension="1536"
           similarityFunction="cosine"
           knnAlgorithm="hnsw"/>
```

### Metadata Field Pattern
- All metadata stored with prefix: `metadata_`
- Example: metadata field "category" → Solr field "metadata_category"

---

## Troubleshooting

### Embedding Generation Fails
- Check OPENAI_API_KEY is set and valid
- Verify API key has embeddings permission
- Check network connectivity to api.openai.com

### Semantic Search Returns No Results
- Verify vector field exists in Solr schema
- Ensure documents are indexed with embeddings
- Check similarity threshold isn't too high

### Solr Connection Errors
- Verify Solr is running on configured URL
- Check solr.url property (should end with /solr/)
- Verify connection timeout settings (default: 10s)

### Chat Memory Not Working
- Verify PostgreSQL is running
- Check chatmemory database exists
- Ensure JDBC initialization enabled
- Verify POSTGRES_USER and POSTGRES_PASSWORD

---

## Performance Tips

### Indexing
- Use batch endpoint for multiple documents
- Single API call to OpenAI for all embeddings
- Single Solr commit for all documents

### Searching
- Keep topK reasonable (default 10)
- Use filters (fq) to constrain search space
- Consider similarity threshold to filter low-relevance results
- Faceting helps refine results

### Solr Tuning
- Ensure vector field has indexed="true"
- HNSW algorithm optimizes KNN performance
- Consider document sampling for large collections

---

## Files to Know

| File | Purpose |
|------|---------|
| `solr/vectorstore/SolrVectorStore.java` | Custom vector store implementation |
| `solr/vectorstore/SolrVectorStoreOptions.java` | Configuration options (record) |
| `indexing/IndexController.java` | Indexing REST endpoints |
| `indexing/IndexService.java` | Indexing business logic |
| `search/SearchController.java` | Search REST endpoints |
| `search/SearchService.java` | Search business logic + semantic search |
| `search/SearchRepository.java` | Solr query execution + field schema |
| `config/SpringAiConfig.java` | Embedding and chat client configuration |
| `config/SolrConfig.java` | Solr client configuration |
| `prompts/system-message.st` | Keyword search prompt |
| `prompts/semantic-search-system-message.st` | Semantic search prompt |

---

## Model Specifications

### Embedding Model
- **Name:** text-embedding-3-small
- **Output:** 1536-dimensional vectors
- **Cost:** ~$0.02 per 1M tokens
- **Speed:** Fast, suitable for real-time embedding

### Chat Model
- **Name:** claude-sonnet-4-5
- **Context:** 200k tokens
- **Capabilities:** JSON output, tool use, vision
- **Purpose:** Query parsing and filter generation

### Search Engine
- **Software:** Apache Solr
- **Vector Algorithm:** HNSW
- **Vector Field Type:** DenseVectorField
- **Similarity Metric:** Cosine

---

