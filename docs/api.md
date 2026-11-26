# REST API reference

Request and response shapes for every endpoint, and how API versioning works. Swagger UI
(`/swagger-ui.html`) is generated from the controllers and is the authority when this page and the
code disagree. Routed from CLAUDE.md.

## API Versioning

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

## API Documentation
- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **OpenAPI Spec**: http://localhost:8080/api-docs
- **Health Check**: http://localhost:8080/actuator/health

## Indexing API

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

## Search APIs

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

- Uses Solr's built-in RRF (Reciprocal Rank Fusion) to combine keyword and vector signals
- Sends one combined-query request; Solr runs both legs and fuses them server-side
- Best for balanced search that leverages both exact matches and semantic understanding
- Schema-agnostic: works with any Solr collection using `_text_` catch-all field
- Fallback on error: if the combined request fails (e.g. no `/hybrid` handler → 404) or the query
  cannot be embedded, degrades to keyword-only → vector-only. An empty fused result is returned as is
- Parameters:
    - `k`: topK results (defaults to 100)
    - `minScore`: minimum cosine similarity [0..1] for the vector leg (`vectorSimilarity minReturn`);
      never applied to the keyword leg or the fused order
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
   - Hybrid: Solr-native RRF (combined query) fusing keyword and vector results
4. For RAG: RetrievalAugmentationAdvisor retrieves hybrid context and injects into prompt
5. Returns documents with scores and enhanced features (highlighting, facets, spell check)

**Implementation**:

- `SearchService` in `src/main/java/dev/aparikh/aipoweredsearch/search/`
- `SearchRepository.executeHybridRerankSearch()` for hybrid search orchestration
- `SearchService.ask()` for RAG question answering
- `AiConfig.ragChatClient` bean with RetrievalAugmentationAdvisor configuration
- `HybridDocumentRetriever` adapting hybrid search to Spring AI's `DocumentRetriever` SPI
