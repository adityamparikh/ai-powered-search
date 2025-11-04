# AI-Powered Search

An intelligent search application that combines Apache Solr's powerful search capabilities with AI-driven semantic search and intelligent query generation. Built with Spring Boot 3.5.7, Spring AI, and Java 21.

## 🚀 Features

### Core Capabilities

- **Semantic Vector Search**: Find documents by meaning using OpenAI embeddings (text-embedding-3-small)
- **Keyword Search**: Traditional full-text search with AI-enhanced query generation
- **Hybrid Search**: Combine semantic similarity with keyword filters for precise results
- **AI-Enhanced Queries**: Anthropic Claude (claude-sonnet-4-5) transforms natural language into structured Solr queries
- **Conversational Context**: Maintains chat memory for context-aware search interactions
- **Auto-Indexing**: Automatic embedding generation during document indexing
- **Batch Operations**: Efficient batch indexing with optimized embedding generation

### Technical Features

- **Production-Ready**: Comprehensive test coverage with unit, integration, and vector store tests
- **Type-Safe Schema**: Explicitly typed metadata fields (strings, integers, doubles, booleans, arrays)
- **SolrCloud**: Distributed Solr with ZooKeeper coordination
- **Vector Similarity**: HNSW algorithm with cosine similarity for fast approximate KNN search
- **RESTful API**: Clean REST endpoints with OpenAPI documentation
- **Virtual Threads**: Leverages Java 21 virtual threads for improved concurrency

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

#### Keyword Search (AI-Enhanced)

**POST** `/api/v1/search`

Performs keyword search with AI-generated Solr queries.

```bash
curl -X POST "http://localhost:8080/api/v1/search" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "find java books with ratings above 4 published recently",
    "collection": "books",
    "searchType": "KEYWORD"
  }'
```

**Request Body:**
```json
{
  "query": "Natural language query",
  "collection": "books",
  "searchType": "SEMANTIC" | "KEYWORD"
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

## 🔧 Configuration

### Application Properties

Key configuration options in `src/main/resources/application.properties`:

```properties
# Solr Configuration
spring.ai.vectorstore.solr.host=http://localhost:8983/solr

# Anthropic Claude Configuration
spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}
spring.ai.anthropic.chat.options.model=claude-sonnet-4-5

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

- **[VECTOR_SEARCH_GUIDE.md](VECTOR_SEARCH_GUIDE.md)**: Complete guide to vector search, indexing, and semantic search
- **[QUICK_REFERENCE.md](QUICK_REFERENCE.md)**: Quick lookup guide for developers
- **[SOLR-SETUP.md](SOLR-SETUP.md)**: Solr schema configuration and setup
- **[CLAUDE.md](CLAUDE.md)**: Instructions for AI assistants working with this codebase

## 🔍 How It Works

### Indexing Flow

1. **Document Reception**: Client sends document(s) via REST API
2. **Preparation**: IndexService converts requests to Document objects
3. **Embedding Generation**: SolrVectorStore generates 1536-dim embeddings via OpenAI
4. **Storage**: Documents + embeddings stored in Solr with typed metadata
5. **Response**: Returns indexed count and document IDs

### Semantic Search Flow

1. **Query Reception**: Client sends natural language query
2. **Query Embedding**: OpenAI generates vector for query text
3. **Filter Generation** (optional): Claude AI generates filter queries
4. **KNN Search**: Solr executes vector similarity search with HNSW
5. **Ranking**: Results ranked by cosine similarity (0-1)
6. **Response**: Returns documents with similarity scores

### Keyword Search Flow

1. **Query Reception**: Client sends natural language query
2. **Schema Retrieval**: System gets field types from Solr
3. **Query Generation**: Claude AI generates structured Solr query
4. **Execution**: Solr executes keyword search with filters/facets
5. **Response**: Returns matched documents with metadata

## 🧠 AI Integration

### Claude AI (Query Generation)

**Model**: claude-sonnet-4-5

**Capabilities:**
- Converts natural language to Solr query syntax
- Understands field types (text, string, numeric, boolean, date)
- Generates filter queries, facets, sorting
- Maintains conversation context for refinement

**System Prompts:**
- `prompts/system-message.st`: Keyword search guidance (122 lines)
- `prompts/semantic-search-system-message.st`: Semantic search guidance (150 lines)

### OpenAI Embeddings

**Model**: text-embedding-3-small

**Specifications:**
- **Dimensions**: 1536
- **Context**: 8,191 tokens
- **Cost**: ~$0.02 per 1M tokens
- **Performance**: Fast, suitable for real-time search

**Usage:**
- Automatic embedding during indexing
- Query embedding for semantic search
- Batch processing for efficiency

## 🏭 Production Deployment

### Docker Build

```bash
./gradlew bootBuildImage
```

### Environment-Specific Configurations

Create environment-specific property files:
- `application-dev.properties`
- `application-staging.properties`
- `application-prod.properties`

### Production Checklist

- [ ] Increase Solr heap size (default: 1GB)
- [ ] Configure Solr replication factor
- [ ] Set up production PostgreSQL
- [ ] Enable SSL for external connections
- [ ] Implement authentication/authorization
- [ ] Configure rate limiting
- [ ] Set up monitoring (Prometheus/Grafana)
- [ ] Configure log aggregation
- [ ] Set up backup strategy

### Health Checks

The application includes Spring Boot Actuator endpoints:
- **Health**: `/actuator/health`
- **Info**: `/actuator/info`

## 🚀 Performance Optimization

### Indexing

- Use batch indexing for multiple documents
- Embeddings generated in single API call
- Single Solr commit per batch

### Searching

- Set appropriate `topK` (default: 10)
- Use similarity threshold to filter results (default: 0.7)
- Request only needed fields with `fl` parameter
- Leverage Solr's query cache

### Monitoring

- Track embedding API costs (OpenAI dashboard)
- Monitor Solr query performance
- Use Spring AI observation framework
- Log slow queries for optimization

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
