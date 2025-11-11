# AI-Powered Search - Quick Reference

Quick lookup guide for developers working with the AI-Powered Search application.

## Table of Contents

- [API Endpoints](#api-endpoints)
- [Configuration](#configuration)
- [Models & Types](#models--types)
- [Common Operations](#common-operations)
- [Troubleshooting](#troubleshooting)

## API Endpoints

### Indexing

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/index/{collection}` | POST | Index single document |
| `/api/v1/index/{collection}/batch` | POST | Batch index documents |

### Search

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/search/{collection}/semantic` | GET | Semantic vector search |
| `/api/v1/search` | POST | Keyword or semantic search |

## Configuration

### Environment Variables

```bash
# Required
export ANTHROPIC_API_KEY="sk-ant-..."
export OPENAI_API_KEY="sk-..."

# Optional
export POSTGRES_USER="postgres"
export POSTGRES_PASSWORD="postgres"
```

### Application Properties

```properties
# Solr
spring.ai.vectorstore.solr.host=http://localhost:8983/solr

# AI Models
spring.ai.anthropic.chat.options.model=claude-sonnet-4-5
spring.ai.openai.embedding.options.model=text-embedding-3-small

# Database
spring.datasource.url=jdbc:postgresql://localhost:5432/chatmemory
```

### Docker Services

```bash
# Start all services
docker-compose up -d

# Stop all services
docker-compose down

# View logs
docker-compose logs -f solr
```

**Services**:
- Solr: http://localhost:8983/solr
- PostgreSQL: localhost:5432
- ZooKeeper: localhost:2181 (internal)

## Models & Types

### Request Models

**IndexRequest**:
```json
{
  "id": "string (optional)",
  "content": "string (required)",
  "metadata": {
    "key": "value"
  }
}
```

**BatchIndexRequest**:
```json
{
  "documents": [IndexRequest, ...]
}
```

**SearchRequest**:
```json
{
  "query": "string",
  "collection": "string",
  "searchType": "SEMANTIC | KEYWORD"
}
```

### Response Models

**IndexResponse**:
```json
{
  "indexed": 2,
  "failed": 0,
  "documentIds": ["id1", "id2"],
  "message": "Success message"
}
```

**SearchResponse**:
```json
{
  "query": "search query",
  "results": [SearchResult, ...],
  "totalResults": 10
}
```

**SearchResult**:
```json
{
  "id": "doc-123",
  "content": "Document content",
  "metadata": {...},
  "score": 0.89
}
```

### Metadata Field Types

| Field | Type | Description |
|-------|------|-------------|
| `author` | string | Document author |
| `category` | string | Document category |
| `type` | string | Document type |
| `language` | string | Document language |
| `size` | string | Size indicator |
| `test` | string | Test metadata |
| `priority` | pint | Integer priority (0-N) |
| `order` | pint | Integer order/sequence |
| `rating` | pdouble | Decimal rating |
| `published` | boolean | Publication status |
| `tags` | strings | Array of tags |
| `metadata_*` | text_general | Dynamic fields (fallback) |

## Common Operations

### Index a Single Document

```bash
curl -X POST "http://localhost:8080/api/v1/index/books" \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Spring Boot makes Java development easier",
    "metadata": {
      "category": "technology",
      "author": "John Doe",
      "tags": ["java", "spring"],
      "priority": 5,
      "published": true,
      "rating": 4.5
    }
  }'
```

### Batch Index Documents

```bash
curl -X POST "http://localhost:8080/api/v1/index/books/batch" \
  -H "Content-Type: application/json" \
  -d '{
    "documents": [
      {
        "id": "doc-1",
        "content": "First document",
        "metadata": {"category": "tech"}
      },
      {
        "content": "Second document",
        "metadata": {"category": "science"}
      }
    ]
  }'
```

### Semantic Search

```bash
curl "http://localhost:8080/api/v1/search/books/semantic?query=machine%20learning%20frameworks&topK=10&minScore=0.7"
```

### Keyword Search

```bash
curl -X POST "http://localhost:8080/api/v1/search" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "find java books with ratings above 4",
    "collection": "books",
    "searchType": "KEYWORD"
  }'
```

### Combined Search (Claude-Enhanced)

```bash
curl -X POST "http://localhost:8080/api/v1/search" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "recent papers about neural networks",
    "collection": "books",
    "searchType": "SEMANTIC"
  }'
```

## Troubleshooting

### Check Solr Status

```bash
# Check collections
curl "http://localhost:8983/solr/admin/collections?action=LIST"

# Check schema
curl "http://localhost:8983/solr/books/schema/fields"

# Check vector field
curl "http://localhost:8983/solr/books/schema/fields/vector"
```

### Common Errors

**"Only DenseVectorField is compatible"**:
```bash
# Restart containers to recreate schema
docker-compose down
docker-compose up -d
```

**"Connection refused" to Solr**:
```bash
# Check if Solr is running
docker-compose ps solr

# View Solr logs
docker-compose logs solr
```

**Empty search results**:
- Lower `minScore` parameter (try 0.5 instead of 0.7)
- Check if documents are indexed: `curl "http://localhost:8983/solr/books/select?q=*:*&rows=0"`

**Slow indexing**:
- Use batch endpoint instead of single document endpoint
- Check OpenAI API rate limits

### Verify Setup

```bash
# 1. Check services are running
docker-compose ps

# 2. Verify collections exist
curl "http://localhost:8983/solr/admin/collections?action=LIST"

# 3. Check document count
curl "http://localhost:8983/solr/books/select?q=*:*&rows=0"

# 4. Test search
curl "http://localhost:8080/api/v1/search/books/semantic?query=test&topK=5"
```

## Performance Tips

### Indexing

1. **Use batch indexing** for multiple documents
2. **Provide IDs** to avoid UUID generation overhead
3. **Limit metadata** to necessary fields
4. **Monitor OpenAI costs** - embeddings charged per token

### Searching

1. **Set appropriate topK** - balance between results and performance
2. **Use similarity threshold** - filter low-relevance results early
3. **Request specific fields** - reduce data transfer
4. **Cache frequent queries** - leverage Solr's caching

### Monitoring

```bash
# Check Solr metrics
curl "http://localhost:8983/solr/admin/metrics"

# View cache statistics
curl "http://localhost:8983/solr/books/admin/mbeans?cat=CACHE"

# Monitor query times
docker-compose logs solr | grep "QTime"
```

## File Locations

### Configuration

- Application config: `src/main/resources/application.properties`
- Solr schema: `solr-config/conf/managed-schema.xml`
- Solr config: `solr-config/conf/solrconfig.xml`
- Docker compose: `docker-compose.yml`
- Init script: `init-solr.sh`

### Code

- Vector store: `src/main/java/dev/aparikh/aipoweredsearch/solr/vectorstore/`
- Indexing: `src/main/java/dev/aparikh/aipoweredsearch/indexing/`
- Search: `src/main/java/dev/aparikh/aipoweredsearch/search/`
- Config: `src/main/java/dev/aparikh/aipoweredsearch/config/`

### Prompts

- Keyword search: `src/main/resources/prompts/system-message.st`
- Semantic search: `src/main/resources/prompts/semantic-search-system-message.st`

### Documentation

- Vector search guide: `VECTOR_SEARCH_GUIDE.md`
- Solr setup: `SOLR-SETUP.md`
- Claude instructions: `CLAUDE.md`
- Quick reference: `QUICK_REFERENCE.md` (this file)

## Key Constants

### Vector Configuration

- **Embedding Model**: text-embedding-3-small
- **Dimensions**: 1536
- **Similarity Function**: Cosine (0-1 scale)
- **KNN Algorithm**: HNSW

### Search Defaults

- **topK**: 10 results
- **Similarity Threshold**: 0.7
- **Collection**: "books" (default)
- **Conversation ID**: "007" (for chat memory)

### Solr

- **Field Type**: knn_vector_1536 (DenseVectorField)
- **ID Field**: id
- **Content Field**: content
- **Vector Field**: vector
- **Metadata Prefix**: metadata_

## Example Workflows

### 1. Initial Setup

```bash
# Clone repository
git clone <repo-url>
cd ai-powered-search

# Set environment variables
export ANTHROPIC_API_KEY="..."
export OPENAI_API_KEY="..."

# Start services
docker-compose up -d

# Verify
curl "http://localhost:8983/solr/admin/collections?action=LIST"

# Run application
./gradlew bootRun
```

### 2. Index Sample Data

```bash
# Index single document
curl -X POST "http://localhost:8080/api/v1/index/books" \
  -H "Content-Type: application/json" \
  -d '{"content":"Sample document","metadata":{"category":"test"}}'

# Verify indexed
curl "http://localhost:8983/solr/books/select?q=*:*"
```

### 3. Perform Searches

```bash
# Semantic search
curl "http://localhost:8080/api/v1/search/books/semantic?query=sample%20document&topK=5"

# Keyword search
curl -X POST "http://localhost:8080/api/v1/search" \
  -H "Content-Type: application/json" \
  -d '{"query":"find test documents","collection":"books","searchType":"KEYWORD"}'
```

### 4. Monitor & Debug

```bash
# View application logs
tail -f logs/application.log

# View Solr logs
docker-compose logs -f solr

# Check embeddings cost
# Monitor OpenAI dashboard for API usage
```

## Useful Curl Commands

### Solr Admin

```bash
# Collection status
curl "http://localhost:8983/solr/admin/collections?action=CLUSTERSTATUS"

# Schema summary
curl "http://localhost:8983/solr/books/schema"

# Field types
curl "http://localhost:8983/solr/books/schema/fieldtypes"

# Delete collection (careful!)
curl "http://localhost:8983/solr/admin/collections?action=DELETE&name=books"
```

### Query Testing

```bash
# Match all documents
curl "http://localhost:8983/solr/books/select?q=*:*&rows=10"

# Filter by field
curl "http://localhost:8983/solr/books/select?q=*:*&fq=category:technology"

# Get specific fields
curl "http://localhost:8983/solr/books/select?q=*:*&fl=id,author,category"

# Facet by field
curl "http://localhost:8983/solr/books/select?q=*:*&facet=true&facet.field=category"
```

## Tips & Best Practices

### Development

1. Use `application-dev.properties` for local development
2. Test with small datasets first
3. Monitor OpenAI API costs during development
4. Use Testcontainers for integration tests

### Production

1. Increase Solr heap size in docker-compose.yml
2. Configure proper Solr replication
3. Set up monitoring (Prometheus/Grafana)
4. Implement rate limiting for API endpoints
5. Use production-grade PostgreSQL setup
6. Enable SSL for external connections

### Security

1. Never commit API keys to git
2. Use environment variables for secrets
3. Implement authentication/authorization
4. Restrict Solr admin access
5. Use read-only database user where possible

## Support & Resources

- **Documentation**: See `VECTOR_SEARCH_GUIDE.md` for comprehensive guide
- **Solr Admin**: http://localhost:8983/solr
- **API Docs**: http://localhost:8080/swagger-ui.html
- **Issues**: Report via GitHub issues
- **Claude Instructions**: See `CLAUDE.md` for AI assistant guidance
