# Technical Details

This document contains in-depth technical information about the AI-Powered Search application.

## Table of Contents
- [Architecture](#architecture)
- [Search Approach Details](#search-approach-details)
- [Processing Flows](#processing-flows)
- [AI Integration](#ai-integration)
- [Configuration](#configuration)
- [Performance Optimization](#performance-optimization)
- [Testing Architecture](#testing-architecture)
- [Code Quality & Automation](#code-quality--automation)
- [Production Deployment](#production-deployment)

## Architecture

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
  - Extends `AbstractObservationVectorStore` for metrics collection
  - Implements document storage with automatic embedding generation
  - Provides vector similarity search using Solr's DenseVectorField

- **IndexService**: Document indexing orchestrator
  - Converts API requests to Spring AI Document format
  - Delegates to SolrVectorStore for embedding generation
  - Handles batch operations efficiently

- **SearchService**: Search strategy coordinator
  - Implements three distinct search approaches
  - Integrates Claude AI for query understanding
  - Manages conversation context via PostgreSQL

- **SearchRepository**: Low-level Solr interface
  - Direct Solr query execution
  - Schema introspection for field types
  - Faceting and aggregation support

- **Chat Memory**: Conversation state management
  - PostgreSQL-backed persistence
  - Conversation ID: "007" (hardcoded)
  - Maintains context across search refinements

## Search Approach Details

### 1. Traditional Keyword Search (`search()`)

**Implementation:** `SearchService.search()` at lines 107-138

**How it works:**
1. Client sends natural language query
2. System retrieves field schema from Solr
3. Claude AI analyzes query and generates structured Solr parameters (q, fq, sort, fl, facets)
4. Solr executes keyword-based search with BM25 ranking
5. Returns matched documents with metadata and facets

**Strengths:**
- ✅ Excellent for exact matches and known-item searches
- ✅ Supports complex boolean queries (AND, OR, NOT)
- ✅ Rich filtering with multiple filter queries (fq)
- ✅ Powerful faceting for aggregation and analytics
- ✅ Deterministic results (same query = same results)
- ✅ Fast query execution with Solr's optimized indices
- ✅ Field boosting and relevance tuning available
- ✅ Cost-effective (no embedding generation at search time)

**Limitations:**
- ❌ Limited understanding of semantic meaning
- ❌ Vocabulary mismatch problem (synonyms not automatically handled)
- ❌ Requires exact or similar keywords to be present
- ❌ Cannot find conceptually similar documents with different terminology
- ❌ Struggles with multilingual or cross-domain queries
- ❌ Dependent on Claude AI availability for query generation

**Best Use Cases:**
- Product catalogs with specific attributes (brand, price, category)
- Legal/compliance documents requiring exact term matches
- Structured data searches with known field names
- Analytics and reporting with faceting requirements
- Multi-criteria filtering scenarios

### 2. Semantic Vector Search (`semanticSearch()`)

**Implementation:** `SearchService.semanticSearch()` at lines 156-215

**How it works:**
1. Client sends natural language query
2. Claude AI parses query to extract filter criteria
3. OpenAI generates 1536-dimension embedding for query text
4. Solr executes KNN similarity search using HNSW algorithm
5. Results ranked by cosine similarity (0-1 scale)
6. Returns semantically similar documents

**Strengths:**
- ✅ Understands semantic meaning and context
- ✅ Finds conceptually similar content regardless of exact keywords
- ✅ Handles vocabulary mismatch and synonyms naturally
- ✅ Works across languages (with multilingual embeddings)
- ✅ Excellent for exploratory search and discovery
- ✅ Captures nuanced relationships between concepts
- ✅ Supports hybrid search (vector similarity + metadata filters)
- ✅ Effective for content recommendation

**Limitations:**
- ❌ Higher computational cost (embedding generation required)
- ❌ Slower than keyword search for large result sets
- ❌ Results can be less predictable/explainable
- ❌ Requires pre-indexed embeddings (storage overhead ~6KB per doc)
- ❌ OpenAI API dependency and cost per search
- ❌ Limited faceting support (not implemented in current VectorStore)
- ❌ Similarity threshold tuning required for optimal results
- ❌ May return conceptually related but contextually incorrect results

**Best Use Cases:**
- Research and academic paper discovery
- Content recommendation systems
- Customer support question matching
- Finding similar products by description
- Cross-lingual information retrieval
- Exploratory "find things like this" queries

### 3. RAG Question Answering (`ask()`)

**Implementation:** `SearchService.ask()` at lines 235-260

**How it works:**
1. Client sends conversational question with optional conversation ID
2. QuestionAnswerAdvisor automatically searches VectorStore for relevant context
3. Retrieved documents added to prompt as context
4. Claude AI generates natural language answer based on context
5. Conversation history maintained in PostgreSQL for follow-up questions
6. Returns conversational answer with conversation ID

**Strengths:**
- ✅ Natural conversational interface
- ✅ Synthesizes information from multiple documents
- ✅ Provides direct answers instead of document lists
- ✅ Maintains context across multiple turns
- ✅ Handles follow-up questions intelligently
- ✅ Reduces cognitive load on users
- ✅ Can explain reasoning and cite sources
- ✅ Excellent for complex questions requiring multiple facts

**Limitations:**
- ❌ Most expensive approach (embeddings + Claude API)
- ❌ Potential for hallucination if context is insufficient
- ❌ No direct access to source documents in current implementation
- ❌ Limited transparency (users don't see which docs were used)
- ❌ Slower response time due to retrieval + generation
- ❌ Answer quality depends on retrieval accuracy
- ❌ Harder to debug when answers are incorrect
- ❌ Context window limitations for very long documents

**Best Use Cases:**
- Customer service chatbots
- Technical documentation Q&A
- Educational tutoring systems
- Internal knowledge base queries
- Complex analytical questions
- Scenarios requiring explanation and reasoning

## Processing Flows

### Indexing Flow

1. **Document Reception**: Client sends document(s) via REST API
2. **Preparation**: IndexService converts requests to Document objects
3. **Embedding Generation**: SolrVectorStore generates 1536-dim embeddings via OpenAI
4. **Storage**: Documents + embeddings stored in Solr with typed metadata
5. **Response**: Returns indexed count and document IDs

### Search Flow (All Types)

1. **Query Reception**: Client sends natural language query
2. **Query Processing**: Varies by search type:
   - **Keyword**: Claude generates Solr query syntax
   - **Semantic**: OpenAI generates query embedding
   - **RAG**: QuestionAnswerAdvisor retrieves context
3. **Execution**: Solr performs search (keyword/vector)
4. **Response Generation**:
   - **Keyword/Semantic**: Return document list
   - **RAG**: Claude generates natural language answer

## AI Integration

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

## Configuration

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

## Performance Optimization

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

## Testing Architecture

The project has comprehensive test coverage across three levels:

### 1. Unit Tests
**Annotation:** `@ExtendWith(MockitoExtension.class)`
- Service layer tests with mocked dependencies
- Controller tests with `@WebMvcTest`
- Test embedding generation, query processing, error handling

### 2. Integration Tests
**Annotation:** `@SpringBootTest` with Testcontainers
- Full application context tests
- Real Solr and PostgreSQL containers
- End-to-end indexing and search flows

### 3. Vector Store Tests
- SolrVectorStore implementation tests
- Embedding generation and storage
- Similarity search validation

### Running Tests

```bash
# Run all tests
./gradlew test

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

## Code Quality & Automation

### SonarQube Integration

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

## Production Deployment

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

## Hybrid Approach Strategy

For optimal results, combine approaches based on your needs:

1. **Discovery + Precision**: Start with semantic search for broad discovery, then apply keyword filters for refinement
2. **Search + Explain**: Use semantic/keyword search to find documents, then RAG to explain or summarize them
3. **Fallback Strategy**: Try keyword search first for speed, fall back to semantic if no results found
4. **Tiered Approach**:
   - Level 1: Quick keyword search
   - Level 2: Semantic search if needed
   - Level 3: RAG for complex questions

The semantic search implementation already demonstrates hybrid capabilities by using Claude AI to generate filters that are applied alongside vector similarity matching.