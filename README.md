# AI-Powered Search

An intelligent search application that combines Apache Solr's powerful search capabilities with Anthropic Claude AI to transform natural language queries into structured search requests. Built with Spring Boot 3.5.4 and Java 21.

## 🚀 Features

- **AI-Enhanced Query Processing**: Converts free-text queries into structured Solr queries using Anthropic Claude
- **Conversational Search**: Maintains chat memory for context-aware search interactions
- **Apache Solr Integration**: Leverages Solr's advanced search and faceting capabilities
- **RESTful API**: Clean REST endpoints with OpenAPI documentation
- **Production Ready**: Comprehensive test coverage with integration tests using Testcontainers

## 🏗️ Architecture

- **Search Service**: Converts natural language queries into structured Solr queries using Claude AI
- **Search Repository**: Handles Solr interactions and query execution
- **Chat Memory**: PostgreSQL-backed conversational context with conversation ID "007"
- **API Layer**: RESTful endpoints with comprehensive OpenAPI documentation

## 📋 Prerequisites

- **Java 21** or higher
- **Docker** and **Docker Desktop** (for Solr and PostgreSQL)
- **Apache Solr 9.6.1** (can be run via Docker)
- **PostgreSQL** (for chat memory storage)
- **Anthropic API Key** (for Claude AI integration)

## 🛠️ Setup & Installation

### 1. Clone the Repository

```bash
git clone https://github.com/adityamparikh/ai-powered-search.git
cd ai-powered-search
```

### 2. Environment Variables

Set the required environment variables:

```bash
export ANTHROPIC_API_KEY=your_anthropic_api_key_here
export POSTGRES_USER=postgres
export POSTGRES_PASSWORD=postgres
```

### 3. Start External Services

The project includes a `docker-compose.yml` for easy setup:

```bash
docker-compose up -d
```

This will start:
- **Solr** at `http://localhost:8983`
- **PostgreSQL** at `localhost:5432`

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

### Search Endpoint

**GET** `/api/v1/search/{collection}?query={query}`

Performs an AI-enhanced search on the specified Solr collection.

#### Parameters

- `collection` (path): The name of the Solr collection to search
- `query` (query): Natural language search query

#### Response

```json
{
  "documents": [
    {
      "id": "1",
      "name": ["Spring Boot Application"],
      "description": ["A sample Spring Boot application with Solr integration"],
      "category": ["framework"],
      "tags": ["java", "spring"]
    }
  ],
  "facetCounts": {
    "category": [
      {
        "value": "framework",
        "count": 1
      }
    ],
    "tags": [
      {
        "value": "spring",
        "count": 2
      },
      {
        "value": "java",
        "count": 1
      }
    ]
  }
}
```

## 🔍 Usage Examples

The API accepts natural language queries and automatically generates appropriate Solr components including:
- **Main Query (q)**: The core search terms
- **Filter Queries (fq)**: Filters to narrow results  
- **Facet Fields**: Fields to provide facet counts for
- **Facet Queries**: Custom facet queries
- **Sort**: Result ordering

### Example 1: Basic Search

```bash
curl "http://localhost:8080/api/v1/search/my-collection?query=find me documents about spring boot applications"
```

**Response:**
```json
{
  "documents": [
    {
      "id": "1",
      "name": ["Spring Boot Application"],
      "description": ["A sample Spring Boot application with Solr integration"],
      "category": ["framework"],
      "tags": ["java", "spring"]
    },
    {
      "id": "3",
      "name": ["Microservices Architecture"],
      "description": ["Building scalable microservices with Spring Boot"],
      "category": ["architecture"],
      "tags": ["microservices", "spring"]
    }
  ],
  "facetCounts": {
    "category": [
      {
        "value": "framework",
        "count": 1
      },
      {
        "value": "architecture",
        "count": 1
      }
    ],
    "tags": [
      {
        "value": "spring",
        "count": 2
      },
      {
        "value": "java",
        "count": 1
      },
      {
        "value": "microservices",
        "count": 1
      }
    ]
  }
}
```

### Example 2: Specific Category Search

```bash
curl "http://localhost:8080/api/v1/search/my-collection?query=show me documentation about search engines"
```

**Response:**
```json
{
  "documents": [
    {
      "id": "2",
      "name": ["Apache Solr Guide"],
      "description": ["Complete guide to Apache Solr search engine"],
      "category": ["documentation"],
      "tags": ["solr", "search"]
    }
  ],
  "facetCounts": {
    "category": [
      {
        "value": "documentation",
        "count": 1
      }
    ],
    "tags": [
      {
        "value": "solr",
        "count": 1
      },
      {
        "value": "search",
        "count": 1
      }
    ]
  }
}
```

### Example 3: Faceted Search with Filtering

Request facet counts and apply filters through natural language:

```bash
curl "http://localhost:8080/api/v1/search/my-collection?query=show me framework documents, group by category and tags, only include java-related items"
```

This generates a Solr query with:
- **Filter Query**: `fq: ["tags:java"]`
- **Facet Fields**: `facet.fields: ["category", "tags"]`

**Response:**
```json
{
  "documents": [
    {
      "id": "1",
      "name": ["Spring Boot Application"],
      "description": ["A sample Spring Boot application with Solr integration"],
      "category": ["framework"],
      "tags": ["java", "spring"]
    }
  ],
  "facetCounts": {
    "category": [
      {
        "value": "framework",
        "count": 1
      }
    ],
    "tags": [
      {
        "value": "java",
        "count": 1
      },
      {
        "value": "spring",
        "count": 1
      }
    ]
  }
}
```

### Example 4: Search with Custom Sorting

```bash
curl "http://localhost:8080/api/v1/search/my-collection?query=find all documents about spring, sort by relevance score descending"
```

This generates: `sort: "score desc"`

### Example 5: Complex Query with Multiple Filters

```bash
curl "http://localhost:8080/api/v1/search/my-collection?query=search for architecture documents that mention microservices, exclude framework category, show facets for tags"
```

This generates:
- **Main Query**: `q: "description:*microservices* OR name:*microservices*"`
- **Filter Queries**: `fq: ["category:architecture", "-category:framework"]`
- **Facet Fields**: `facet.fields: ["tags"]`

### Example 6: Using cURL with JSON Headers

```bash
curl -X GET \
  -H "Accept: application/json" \
  -H "Content-Type: application/json" \
  "http://localhost:8080/api/v1/search/my-collection?query=find documentation with search functionality, show category breakdown"
```

## 🧪 Testing

### Run All Tests

```bash
./gradlew test
```

### Run Specific Test Classes

```bash
./gradlew test --tests "SearchIntegrationTest"
./gradlew test --tests "SolrSearchIntegrationTest" 
```

### Test Structure

- **Integration Tests**: Full application context tests using Testcontainers
- **Unit Tests**: Isolated component testing
- **Repository Tests**: Solr integration testing with test containers

## 🔧 Configuration

### Application Properties

Key configuration options in `src/main/resources/application.properties`:

```properties
# Solr Configuration
spring.data.solr.host=http://localhost:8983/solr

# Anthropic AI Configuration  
spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}
spring.ai.anthropic.chat.model=claude-sonnet-4-0

# PostgreSQL Chat Memory
spring.datasource.url=jdbc:postgresql://localhost:5432/chatmemory
spring.datasource.username=${POSTGRES_USER:postgres}
spring.datasource.password=${POSTGRES_PASSWORD:postgres}

# Chat Memory Configuration
spring.ai.chat.memory.repository.jdbc.initialize-schema=always
spring.ai.chat.memory.repository.type=jdbc
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `ANTHROPIC_API_KEY` | Your Anthropic API key (required) | - |
| `POSTGRES_USER` | PostgreSQL username | `postgres` |
| `POSTGRES_PASSWORD` | PostgreSQL password | `postgres` |

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

### Health Checks

The application includes Spring Boot Actuator endpoints:
- **Health**: `/actuator/health`
- **Info**: `/actuator/info`

## 🔍 How It Works

1. **Query Reception**: REST endpoint receives natural language query
2. **Schema Inspection**: System retrieves available fields from target Solr collection
3. **AI Processing**: Claude AI converts the natural language query into structured Solr query syntax
4. **Search Execution**: Generated query is executed against Solr
5. **Response Assembly**: Results are formatted and returned with facet information
6. **Memory Storage**: Conversation context is stored for future interactions

## 🧠 Natural Language to Solr Mapping

The AI understands various natural language patterns and converts them to appropriate Solr components:

### Query Patterns

| Natural Language | Generated Component | Example |
|------------------|--------------------|---------| 
| "find documents about X" | Main query (q) | `q: "name:*spring* OR description:*spring*"` |
| "only include Y items" | Filter query (fq) | `fq: ["category:framework"]` |
| "exclude Z category" | Negative filter | `fq: ["-category:documentation"]` |
| "group by field" | Facet fields | `facet.fields: ["category", "tags"]` |
| "sort by relevance/date" | Sort clause | `sort: "score desc"` |
| "show breakdown of X" | Facet fields | `facet.fields: ["X"]` |

### Sample AI Query Transformations

#### Example 1: Basic Search
**Input**: "find me documents about spring boot applications"

**AI Generated Solr Query**:
```json
{
  "q": "name:*spring* OR description:*spring* OR name:*boot* OR description:*boot*",
  "fq": ["tags:spring"],
  "sort": "score desc",
  "facet.fields": ["category", "tags"],
  "facet.query": null
}
```

#### Example 2: Filtered Faceted Search
**Input**: "show me framework documents, group by category and tags, only include java-related items"

**AI Generated Solr Query**:
```json
{
  "q": "category:framework",
  "fq": ["tags:java"],
  "sort": "score desc", 
  "facet.fields": ["category", "tags"],
  "facet.query": null
}
```

#### Example 3: Complex Multi-Filter Search
**Input**: "search for architecture documents that mention microservices, exclude framework category, show facets for tags"

**AI Generated Solr Query**:
```json
{
  "q": "description:*microservices* OR name:*microservices*",
  "fq": ["category:architecture", "-category:framework"],
  "sort": "score desc",
  "facet.fields": ["tags"],
  "facet.query": null
}
```

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests for new functionality
5. Ensure all tests pass
6. Submit a pull request

## 🆘 Support

- **Issues**: [GitHub Issues](https://github.com/adityamparikh/ai-powered-search/issues)
- **Documentation**: Check the `/docs` endpoint when running
- **API Reference**: Swagger UI at `/swagger-ui.html`
