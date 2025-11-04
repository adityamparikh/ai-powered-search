# Solr Vector Search Setup

This document explains the Solr configuration for vector search in the AI-Powered Search application.

## Schema Management Approach

This project uses a **production-ready schema file approach** with ZooKeeper configuration management:

- **Schema File**: `solr-config/conf/managed-schema.xml` - Declarative schema definition
- **Config Management**: Uploaded to ZooKeeper via `init-solr.sh`
- **Configset Name**: `ai-powered-search-config`

All collections use the shared configset stored in ZooKeeper, ensuring consistency across the SolrCloud cluster.

## Quick Start

### Start Solr with Vector Support

```bash
docker-compose up -d
```

This will:
1. Start ZooKeeper for SolrCloud coordination
2. Start Solr in SolrCloud mode
3. Create two collections: `books` and `vector_search`
4. Configure vector fields automatically

### Verify Setup

```bash
# Check collections
curl "http://localhost:8983/solr/admin/collections?action=LIST"

# Verify vector field
curl "http://localhost:8983/solr/books/schema/fields/vector"

# Verify field type
curl "http://localhost:8983/solr/books/schema/fieldtypes/knn_vector_1536"
```

## Vector Field Configuration

### Field Type: `knn_vector_1536`
- **Class**: `solr.DenseVectorField`
- **Dimensions**: 1536 (matches OpenAI text-embedding-3-small)
- **Similarity Function**: cosine
- **Algorithm**: HNSW (Hierarchical Navigable Small World)

### Vector Field: `vector`
- **Type**: knn_vector_1536
- **Indexed**: true
- **Stored**: true

### Additional Fields

- **content** (text_general): For document text content

### Metadata Fields (based on IndexRequest.java)

The schema includes explicit fields for common metadata types:

**String fields:**
- **category**: Document category
- **author**: Document author
- **type**: Document type
- **language**: Document language
- **size**: Document size indicator
- **test**: Test-related metadata

**Numeric fields:**
- **priority** (pint): Integer priority value
- **order** (pint): Integer order/sequence value
- **rating** (pdouble): Double rating value

**Boolean field:**
- **published**: Publication status

**Multi-valued field:**
- **tags** (strings): Array of tags

**Fallback:**
- **metadata_*** (dynamic field): For any other metadata not covered by explicit fields (multiValued, text_general)

## Collections Created

1. **books** - With full vector support
2. **vector_search** - With full vector support

Both collections are configured identically with vector search capabilities.

## Using the Vector Store

The `SolrVectorStore` implementation automatically uses these collections for:
- Document indexing with embedding generation
- Vector similarity search using KNN
- Hybrid search (combining traditional and semantic search)

Example usage in code:
```java
SolrVectorStore vectorStore = SolrVectorStore.builder(solrClient, "books", embeddingModel)
    .options(SolrVectorStoreOptions.defaults())
    .build();
```

## Troubleshooting

### "Only DenseVectorField is compatible with Vector Query Parsers"

If you see this error, the collection doesn't have the vector field configured.

**Solution**: Restart containers to re-run initialization:
```bash
docker-compose down
docker-compose up -d
```

### Adding Custom Collections

Modify `init-solr.sh` and add your collection:
```bash
create_collection_with_vectors "my_collection"
```

Then restart:
```bash
docker-compose down
docker-compose up -d
```

## Accessing Solr Admin UI

Open in browser: http://localhost:8983/solr/

Navigate to:
- **Collections** → See created collections
- **Schema** → View field configurations
- **Query** → Test searches

## Schema Details

The initialization script (`init-solr.sh`) configures the following field types and fields:

### Vector Field Type

```json
{
  "add-field-type": {
    "name": "knn_vector_1536",
    "class": "solr.DenseVectorField",
    "vectorDimension": 1536,
    "similarityFunction": "cosine",
    "knnAlgorithm": "hnsw"
  }
}
```

### Core Fields

```json
{
  "add-field": {
    "name": "vector",
    "type": "knn_vector_1536",
    "stored": true,
    "indexed": true
  }
}
```

```json
{
  "add-field": {
    "name": "content",
    "type": "text_general",
    "stored": true,
    "indexed": true
  }
}
```

### Explicit Metadata Fields

The schema now includes properly typed fields matching the `IndexRequest.java` data model:

```json
// String fields: category, author, type, language, size, test
{
  "add-field": {
    "name": "category",
    "type": "string",
    "stored": true,
    "indexed": true
  }
}

// Integer fields: priority, order
{
  "add-field": {
    "name": "priority",
    "type": "pint",
    "stored": true,
    "indexed": true
  }
}

// Double field: rating
{
  "add-field": {
    "name": "rating",
    "type": "pdouble",
    "stored": true,
    "indexed": true
  }
}

// Boolean field: published
{
  "add-field": {
    "name": "published",
    "type": "boolean",
    "stored": true,
    "indexed": true
  }
}

// Multi-valued field: tags
{
  "add-field": {
    "name": "tags",
    "type": "strings",
    "stored": true,
    "indexed": true,
    "multiValued": true
  }
}
```

### Dynamic Field (Fallback)

```json
{
  "add-dynamic-field": {
    "name": "metadata_*",
    "type": "text_general",
    "stored": true,
    "indexed": true,
    "multiValued": true
  }
}
```

This dynamic field serves as a fallback for any metadata not covered by the explicit fields above.

## Performance Tuning

### HNSW Parameters

The HNSW algorithm provides approximate nearest neighbor search with excellent performance. Default parameters are optimized for general use.

To tune for your specific use case, you can modify the field type in `init-solr.sh` with additional parameters:
- `hnswMaxConnections` - Max connections per layer (default: 16)
- `hnswBeamWidth` - Search beam width (default: 100)

### Heap Size

Default: 1GB (`SOLR_HEAP: "1g"` in docker-compose.yml)

For production, increase based on your data size:
```yaml
solr:
  environment:
    SOLR_HEAP: "4g"  # 4GB heap
```

## Testing Vector Search

Test the vector field with a simple query:

```bash
curl -X POST "http://localhost:8983/solr/books/select" \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "*:*",
    "limit": 10
  }'
```

For KNN search (requires documents with vectors):
```bash
curl -X POST "http://localhost:8983/solr/books/select" \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "{!knn f=vector topK=10}[0.1,0.1,0.1,...]",
    "limit": 10
  }'
```

Note: Replace `[0.1,0.1,0.1,...]` with an actual 1536-dimensional vector.
