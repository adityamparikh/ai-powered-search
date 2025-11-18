# AI-Powered Search

Apache Solr search with AI-driven query generation and semantic vector search. Built with Spring Boot 3.5.7, Spring AI, and Java 21.

## 🚀 Features

- **Keyword Search**: Claude AI converts natural language to Solr queries with enhanced field boosting
- **Semantic Search**: OpenAI embeddings (1536-dim) for vector similarity search
- **Hybrid Search (RRF)**: Native Reciprocal Rank Fusion combining keyword and vector signals
- **RAG Q&A**: Retrieval-augmented generation for conversational answers
- **Auto-Indexing**: Automatic embedding generation during document storage
- **Schema-Agnostic**: Works with any Solr schema using `_text_` catch-all field
- **Prompt Caching**: Anthropic prompt caching for up to 90% cost reduction
- **SolrCloud**: Distributed search with ZooKeeper

## 🏗️ Architecture

### System Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        Client Application                        │
└───────────────┬─────────────────────────┬──────────────────────┘
                │                         │
        Indexing API              Search API
                │                         │
    ┌───────────▼──────────┐   ┌─────────▼────────────┐
    │  IndexController     │   │  SearchController    │
    │  - Single document   │   │  - Semantic search   │
    │  - Batch indexing    │   │  - Keyword search    │
    └───────────┬──────────┘   └─────────┬────────────┘
                │                        │
    ┌───────────▼──────────┐   ┌─────────▼────────────┐
    │   IndexService       │   │   SearchService      │
    │  - Auto embeddings   │   │  - Query generation  │
    └───────────┬──────────┘   └─────────┬────────────┘
                │                        │
                │              ┌─────────▼────────────┐
                │              │  SearchRepository    │
                │              │  - Schema retrieval  │
                │              └─────────┬────────────┘
                │                        │
    ┌───────────▼────────────────────────▼────────────┐
    │           SolrVectorStore (Spring AI)           │
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
    │         Apache Solr 9.9.0 + ZooKeeper            │
    │  - DenseVectorField (HNSW, cosine)              │
    │  - KNN search                                    │
    │  - Typed schema fields                           │
    │  - PostgreSQL (chat memory)                      │
    └──────────────────────────────────────────────────┘
```

### Key Components

- **SolrVectorStore**: Custom Spring AI vector store implementation for Solr
- **IndexService**: Handles document indexing with automatic embedding generation
- **SearchService**: Orchestrates both semantic and keyword search
- **SearchRepository**: Solr query execution and schema introspection
- **Chat Memory**: PostgreSQL-backed conversation history (conversation ID: "007")

## 📋 Prerequisites

- **Java 21** or higher
- **Docker** and **Docker Desktop** (for Solr, ZooKeeper, and PostgreSQL)
- **Apache Solr 9.9.0** (provided via Docker)
- **ZooKeeper 3.9** (provided via Docker)
- **PostgreSQL 16** (provided via Docker)
- **Anthropic API Key** (for Claude AI)
- **OpenAI API Key** (for embeddings)

## 🛠️ Setup & Installation

### 1. Clone the Repository

```bash
git clone https://github.com/adityamparikh/ai-powered-search.git
cd ai-powered-search
```

### 2. Environment Variables

Set the required environment variables:

```bash
# Required
export ANTHROPIC_API_KEY="sk-ant-your-key-here"
export OPENAI_API_KEY="sk-your-key-here"

# Optional (defaults provided)
export POSTGRES_USER="postgres"
export POSTGRES_PASSWORD="postgres"

# Optional: Anthropic Prompt Caching (enabled by default)
export ANTHROPIC_PROMPT_CACHING_ENABLED=true
export ANTHROPIC_PROMPT_CACHING_STRATEGY=SYSTEM_AND_TOOLS
```

### 3. Start External Services

The project includes a `docker-compose.yml` for easy setup:

```bash
docker-compose up -d
```

This will start:
- **Solr** at `http://localhost:8983` (with ZooKeeper coordination)
- **ZooKeeper** at `localhost:2181` (internal)
- **PostgreSQL** at `localhost:5432`

The init script automatically:
- Uploads the custom schema to ZooKeeper
- Creates the "books" collection with vector support
- Configures all field types and metadata fields

### 4. Build the Application

```bash
./gradlew clean build
```

### 5. Run the Application

```bash
./gradlew bootRun
```

The application will start at `http://localhost:8080`

## 📖 API Documentation

### Interactive API Documentation

Once the application is running, access the interactive API documentation at:
- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **OpenAPI Spec**: http://localhost:8080/api-docs

### Indexing Endpoints

#### Index Single Document

**POST** `/api/v1/index/{collection}`

```bash
curl -X POST "http://localhost:8080/api/v1/index/books" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "doc-123",
    "content": "Spring Boot simplifies Java application development",
    "metadata": {
      "category": "technology",
      "author": "John Doe",
      "tags": ["java", "spring", "framework"],
      "priority": 5,
      "published": true,
      "rating": 4.5
    }
  }'
```

**Response:**
```json
{
  "indexed": 1,
  "failed": 0,
  "documentIds": ["doc-123"],
  "message": "Successfully indexed 1 document(s)"
}
```

#### Batch Index Documents

**POST** `/api/v1/index/{collection}/batch`

```bash
curl -X POST "http://localhost:8080/api/v1/index/books/batch" \
  -H "Content-Type: application/json" \
  -d '{
    "documents": [
      {
        "content": "TensorFlow is a machine learning framework",
        "metadata": {"category": "ml", "language": "python"}
      },
      {
        "content": "PyTorch provides dynamic computational graphs",
        "metadata": {"category": "ml", "language": "python"}
      }
    ]
  }'
```

### Search Endpoints

#### Semantic Search

**GET** `/api/v1/search/{collection}/semantic`

Performs vector similarity search using OpenAI embeddings.

```bash
curl "http://localhost:8080/api/v1/search/books/semantic?query=frameworks%20for%20building%20web%20applications&topK=10&minScore=0.7"
```

**Parameters:**
- `query`: Natural language search query (required)
- `topK`: Number of results to return (default: 10)
- `minScore`: Minimum similarity score 0-1 (default: 0.7)

**Response:**
```json
{
  "query": "frameworks for building web applications",
  "results": [
    {
      "id": "doc-123",
      "content": "Spring Boot simplifies Java application development",
      "metadata": {
        "category": "technology",
        "author": "John Doe",
        "tags": ["java", "spring", "framework"]
      },
      "score": 0.89
    }
  ],
  "totalResults": 5
}
```

#### Hybrid Search with RRF

**GET** `/api/v1/search/{collection}/hybrid`

Performs hybrid search using Native Reciprocal Rank Fusion (RRF) to combine keyword and vector signals.

```bash
curl "http://localhost:8080/api/v1/search/books/hybrid?query=machine%20learning%20frameworks&topK=10"
```

**Parameters:**

- `query`: Natural language search query (required)
- `topK`: Number of results to return (default: 100)
- `filter`: Solr filter query (optional)

**How it works:**

- Uses Solr's native `{!rrf}` query parser (Solr 9.8+)
- Combines edismax keyword search on `_text_` field with KNN vector search
- RRF formula: `score = sum(1 / (k + rank))` balances both signals
- Schema-agnostic: works with any Solr collection

**Response:**

```json
{
  "documents": [
    {
      "id": "doc-789",
      "content": "TensorFlow is a popular ML framework",
      "metadata": {"category": "ml"},
      "score": 0.92
    }
  ],
  "facetCounts": {},
  "highlighting": {},
  "spellCheckSuggestion": null
}
```

#### Keyword Search (AI-Enhanced)

**GET** `/api/v1/search/{collection}`

Performs keyword search with AI-generated Solr queries.

```bash
curl "http://localhost:8080/api/v1/search/books?query=find%20java%20books%20with%20high%20ratings"
```

**Parameters:**
- `query`: Natural language search query (required)

**Response:**
```json
{
  "documents": [
    {
      "id": "doc-456",
      "content": "Java programming guide",
      "metadata": {"category": "technology", "rating": 4.8}
    }
  ],
  "facets": {
    "category": {"technology": 15, "programming": 10}
  }
}
```

#### RAG Question Answering

**POST** `/api/v1/search/ask`

Performs conversational question-answering using RAG (Retrieval-Augmented Generation).

```bash
curl -X POST "http://localhost:8080/api/v1/search/ask" \
  -H "Content-Type: application/json" \
  -d '{
    "question": "What are the benefits of using Spring Boot for microservices?",
    "conversationId": "user-123"
  }'
```

**Request Body:**
```json
{
  "question": "Your question here",
  "conversationId": "optional-conversation-id"
}
```

**Response:**
```json
{
  "answer": "Spring Boot offers several benefits for microservices development...",
  "conversationId": "user-123",
  "sources": []
}
```

## 🔍 Usage Examples

### Semantic Search Examples

Semantic search finds documents by meaning, not just keywords.

#### Example 1: Conceptual Search

```bash
# Find documents about machine learning frameworks
curl "http://localhost:8080/api/v1/search/books/semantic?query=deep%20learning%20libraries&topK=5"
```

**Matches:**
- "TensorFlow is a machine learning framework" (score: 0.87)
- "PyTorch provides dynamic computational graphs" (score: 0.85)
- "Keras simplifies neural network building" (score: 0.82)

#### Example 2: Cross-Language Conceptual Match

```bash
# Search in English, find conceptually similar content
curl "http://localhost:8080/api/v1/search/books/semantic?query=optimizing%20neural%20networks"
```

**Matches documents about:**
- Training techniques
- Hyperparameter tuning
- Model optimization
- Performance improvements

### Schema-Agnostic Design

The hybrid RRF search implementation is **schema-agnostic**, meaning it works with any Solr collection without requiring
specific field names:

- **Uses `_text_` catch-all field**: Solr's built-in field that aggregates all text content
- **No field assumptions**: Doesn't require specific fields like `title`, `content`, or `category`
- **Graceful degradation**: Works even if your schema has custom field names
- **Easy integration**: Drop-in search for any existing Solr collection

This makes the search functionality portable across different schemas and use cases.

### Keyword Search Examples

Keyword search uses Claude AI to generate precise Solr queries.

#### Example 1: Filtered Search

```bash
curl -X POST "http://localhost:8080/api/v1/search" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "find programming books about java with high ratings",
    "collection": "books",
    "searchType": "KEYWORD"
  }'
```

**Claude generates:**
```json
{
  "queryString": "content:(programming AND java)",
  "filterQueries": ["category:technology", "rating:[4.0 TO *]"],
  "sort": "rating desc"
}
```

#### Example 2: Faceted Search

```bash
curl -X POST "http://localhost:8080/api/v1/search" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "show me framework documents, group by category and language",
    "collection": "books",
    "searchType": "KEYWORD"
  }'
```

**Claude generates:**
```json
{
  "queryString": "category:framework",
  "facetFields": ["category", "language"]
}
```

### Hybrid Search Example

Combine semantic similarity with keyword filters:

```bash
# Step 1: Semantic search with Claude-enhanced filters
curl -X POST "http://localhost:8080/api/v1/search" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "recent papers about neural networks published in 2024",
    "collection": "books",
    "searchType": "SEMANTIC"
  }'
```

**Claude generates filters:**
```json
{
  "filterQueries": ["published:true", "year:2024"],
  "fieldList": ["id", "title", "content", "author"]
}
```

**Then KNN search with filters applied**

## 🧪 Testing

### Run All Tests

```bash
./gradlew test
```

### Run Specific Test Classes

```bash
# Integration tests
./gradlew test --tests "SearchIntegrationTest"
./gradlew test --tests "IndexIntegrationTest"

# Service tests
./gradlew test --tests "SearchServiceTest"
./gradlew test --tests "IndexServiceTest"

# Controller tests
./gradlew test --tests "SearchControllerTest"
./gradlew test --tests "IndexControllerTest"
```

### Test Structure

The project has comprehensive test coverage across three levels:

**1. Unit Tests** (@ExtendWith(MockitoExtension.class)):
- Service layer tests with mocked dependencies
- Controller tests with @WebMvcTest
- Test embedding generation, query processing, error handling

**2. Integration Tests** (@SpringBootTest with Testcontainers):
- Full application context tests
- Real Solr and PostgreSQL containers
- End-to-end indexing and search flows

**3. Vector Store Tests**:
- SolrVectorStore implementation tests
- Embedding generation and storage
- Similarity search validation

## 📊 Code Quality & Automation

### SonarQube Integration

The project includes SonarQube integration for continuous code quality monitoring:

```bash
# Run SonarQube analysis
./gradlew sonarqube \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.token=your-token
```

**Quality Metrics Tracked:**
- Code coverage (via JaCoCo)
- Code smells and technical debt
- Security vulnerabilities
- Maintainability rating
- Reliability rating

### JaCoCo Code Coverage

Code coverage reports are automatically generated:

```bash
# Run tests with coverage
./gradlew test jacocoTestReport

# View coverage report
open build/reports/jacoco/test/html/index.html
```

### Claude AI PR Reviews

Pull requests are automatically reviewed by Claude AI for:

- **Code Quality**: Clean code principles, SOLID compliance, design patterns
- **Security**: OWASP Top 10, injection vulnerabilities, authentication issues
- **Performance**: Algorithm efficiency, query optimization, resource management
- **Spring Boot**: Best practices, RESTful design, configuration patterns
- **AI/ML**: Prompt engineering, vector store efficiency, RAG implementation

See [Claude PR Review Documentation](docs/CLAUDE_PR_REVIEW.md) for setup and configuration.

### Comprehensive Javadocs

All public classes and methods include detailed Javadocs with:
- Purpose and functionality description
- Parameter documentation
- Return value documentation
- Usage examples
- Cross-references to related classes

Generate Javadoc HTML:
```bash
./gradlew javadoc
open build/docs/javadoc/index.html
```

## 🔧 Configuration

### Application Properties

Key configuration options in `src/main/resources/application.properties`:

```properties
# Solr Configuration
spring.ai.vectorstore.solr.host=http://localhost:8983/solr

# Anthropic Claude Configuration
spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}
spring.ai.anthropic.chat.options.model=claude-sonnet-4-5
# Anthropic Prompt Caching (90% cost reduction on cache hits)
spring.ai.anthropic.prompt-caching.enabled=${ANTHROPIC_PROMPT_CACHING_ENABLED:true}
spring.ai.anthropic.prompt-caching.strategy=${ANTHROPIC_PROMPT_CACHING_STRATEGY:SYSTEM_AND_TOOLS}
# OpenAI Embeddings Configuration
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.embedding.options.model=text-embedding-3-small

# PostgreSQL Chat Memory
spring.datasource.url=jdbc:postgresql://localhost:5432/chatmemory
spring.datasource.username=${POSTGRES_USER:postgres}
spring.datasource.password=${POSTGRES_PASSWORD:postgres}

# Virtual Threads (Java 21)
spring.threads.virtual.enabled=true
```

### Metadata Field Types

The schema supports typed metadata fields matching `IndexRequest.java`:

| Field | Type | Description |
|-------|------|-------------|
| `author` | string | Document author |
| `category` | string | Document category |
| `type` | string | Document type |
| `language` | string | Programming language |
| `priority` | pint | Integer priority |
| `order` | pint | Sequence number |
| `rating` | pdouble | Decimal rating |
| `published` | boolean | Publication status |
| `tags` | strings | Array of tags |
| `metadata_*` | text_general | Dynamic fields (fallback) |

### Vector Configuration

- **Embedding Model**: OpenAI text-embedding-3-small
- **Dimensions**: 1536
- **Similarity**: Cosine (0-1 scale)
- **Algorithm**: HNSW (Hierarchical Navigable Small World)
- **Field**: vector (type: knn_vector_1536)

## 📚 Documentation

Comprehensive documentation is available:

- **[CLAUDE.md](CLAUDE.md)**: Instructions for AI assistants working with this codebase (start here!)
- **[SOLR_ENHANCEMENTS.md](SOLR_ENHANCEMENTS.md)**: Native RRF, schema-agnostic design, and Solr features
- **[TECHNICAL_DETAILS.md](TECHNICAL_DETAILS.md)**: In-depth technical implementation details
- **[VECTOR_SEARCH_GUIDE.md](VECTOR_SEARCH_GUIDE.md)**: Complete guide to vector search, indexing, and semantic search
- **[QUICK_REFERENCE.md](QUICK_REFERENCE.md)**: Quick lookup guide for developers
- **[SOLR-SETUP.md](SOLR-SETUP.md)**: Solr schema configuration and setup

## 🔍 Search Approaches

| Feature                 | Traditional Keyword        | Hybrid RRF                          | Semantic Vector                          | RAG Q&A                              |
|-------------------------|----------------------------|-------------------------------------|------------------------------------------|--------------------------------------|
| **Method**              | `search()`                 | `executeHybridRerankSearch()`       | `semanticSearch()`                       | `ask()`                              |
| **When AI is Used**     | Claude converts NL to Solr | OpenAI generates embedding          | Claude parses filters + OpenAI embedding | OpenAI retrieval + Claude generation |
| **Search Mechanism**    | Solr BM25 on keywords      | Native RRF: keyword + vector fusion | Solr KNN cosine similarity               | Vector retrieval + LLM               |
| **Response**            | Documents with metadata    | Documents ranked by RRF score       | Documents with similarity scores         | Natural language answer              |
| **Speed**               | ~100ms                     | ~600ms (RRF fusion)                 | ~500ms (embedding)                       | ~2s (retrieval + gen)                |
| **Cost**                | $0.001 (Claude only)       | $0.015 (OpenAI embedding)           | $0.01 (Claude + OpenAI)                  | $0.05 (OpenAI + Claude)              |
| **Predictability**      | High: deterministic        | Medium-High: stable RRF             | Medium: embeddings vary                  | Low: non-deterministic               |
| **Schema Requirements** | Any (uses `_text_`)        | Any (uses `_text_` + vector)        | Vector field required                    | Vector field required                |
| **Filtering**           | ✅ Native Solr fq           | ✅ Native Solr fq                    | ✅ Converted to Solr                      | ⚠️ Via initial retrieval             |

### When to Use Each Approach

| Use Case                      | Best Approach  | Why                                                        |
|-------------------------------|----------------|------------------------------------------------------------|
| Product catalog search        | Keyword        | Need exact filters, facets, deterministic results          |
| General search (best of both) | **Hybrid RRF** | Balances keyword precision with semantic understanding     |
| Research paper discovery      | Semantic       | Concept matching more important than exact terms           |
| Customer support Q&A          | RAG            | Users need synthesized answers, not document lists         |
| Legal document search         | Keyword        | Exact terminology critical, need audit trail               |
| "Find similar" features       | Semantic       | Vector similarity captures conceptual relationships        |
| Technical documentation help  | RAG            | Complex questions need context from multiple docs          |
| E-commerce with typos         | **Hybrid RRF** | Vector search handles misspellings, keyword adds precision |

See [TECHNICAL_DETAILS.md](TECHNICAL_DETAILS.md) for implementation details.

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests for new functionality
5. Ensure all tests pass
6. Submit a pull request

## 📄 License

[Add your license information here]

## 🆘 Support

- **Issues**: [GitHub Issues](https://github.com/adityamparikh/ai-powered-search/issues)
- **Documentation**: See `/docs` directory
- **API Reference**: Swagger UI at `/swagger-ui.html`
- **Guides**: Check VECTOR_SEARCH_GUIDE.md for comprehensive documentation

## 🙏 Acknowledgments

Built with:
- [Spring Boot](https://spring.io/projects/spring-boot) - Application framework
- [Spring AI](https://docs.spring.io/spring-ai/reference/) - AI integration
- [Apache Solr](https://solr.apache.org/) - Search platform
- [Anthropic Claude](https://www.anthropic.com/) - Query generation AI
- [OpenAI](https://openai.com/) - Embedding generation
- [ZooKeeper](https://zookeeper.apache.org/) - Distributed coordination
- [PostgreSQL](https://www.postgresql.org/) - Chat memory storage
