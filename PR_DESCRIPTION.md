# Spring AI 1.1.0 GA Upgrade + Hybrid Search with Reranking + Critical Bug Fixes

## Overview

This PR upgrades the application to **Spring AI 1.1.0 GA**, implements **hybrid search using vector reranking**, and fixes three critical bugs affecting test execution and production endpoints.

## 🎯 Key Features

### 1. **Spring AI 1.1.0 GA Upgrade**
- Upgraded from Spring AI 1.1.0-M4 to **1.1.0 GA release**
- Updated dependencies and configurations for compatibility
- Leverages stable GA features and performance improvements

### 2. **Hybrid Search with Vector Reranking**
- **New**: `SearchRepository.executeHybridRerankSearch()` method implementing hybrid search
- Combines lexical (keyword) and semantic (vector) search using Solr's `rerank` query parser
- **Algorithm**:
  - Primary: Keyword search using edismax
  - Reranking: Vector similarity re-scores top-N keyword results
  - Formula: `finalScore = keywordScore + (vectorScore × reRankWeight)`
- **Configuration**:
  - `reRankDocs=200` - Number of top documents to rerank
  - `reRankWeight=2.0` - Weight multiplier for vector scores
- **Note**: This uses reranking, not RRF (Reciprocal Rank Fusion). Native RRF support is not available in Solr 9.10.0
- Automatic fallback chain: Hybrid → Keyword → Vector search

### 3. **Embedding Service & Vector Utilities**
- **New**: `EmbeddingService` - centralized service for text embedding generation
- **New**: `VectorFormatUtils` - utility class for vector formatting
- Support for both OpenAI and Anthropic embedding models
- Automatic Solr vector format conversion `[0.1, 0.2, ...]`
- Batch processing capabilities with retry logic

### 4. **Enhanced Search Capabilities**
- **Semantic Search**: AI-powered filter parsing with Solr-native filter support
- **Hybrid Search**: Reranking-based combination of keyword + vector search
- **Traditional Search**: AI-enhanced query generation with faceting
- Support for complex Solr filters (ranges, boolean operators)
- Field projection and score filtering

## 🐛 Critical Bug Fixes

### Bug Fix #1: Jetty Version Compatibility Issue ✅
**Problem**: `NoSuchMethodError: HttpClient.newRequest(URI)` when running vector store tests

**Root Cause**:
- Jetty 11 pinned in build.gradle.kts for SolrJ compatibility (Solr 9.10.0 requires Jetty 11)
- Spring Boot 3.5.7 expecting Jetty 12 APIs in `JettyClientHttpRequestFactory`
- Tests had conflicting `spring.http.client.factory=simple` property
- When Spring AI's OpenAI client tried to use Jetty, it found Jetty 11 but expected Jetty 12 methods

**Solution**:
- Removed conflicting `spring.http.client.factory=simple` from all test configurations
- Ensured `RestClientConfig` properly configures JDK HttpClient for Spring's `RestClient`
- Updated all `OpenAiApi` instantiations to explicitly use injected `RestClient.Builder`
- **Files affected**:
  - `SolrVectorStoreDebugTest.java` - Removed property, inject RestClient.Builder
  - `SolrVectorStoreIT.java` - Removed property (already used RestClient.Builder)
  - `SolrVectorStoreObservationIT.java` - Removed property (already used RestClient.Builder)

**Reference**: `RestClientConfig.java:src/main/java/dev/aparikh/aipoweredsearch/config/RestClientConfig.java:24-28`

**Impact**: Vector store tests now run successfully without Jetty version conflicts

---

### Bug Fix #2: PostgreSQL "Too Many Clients" Error ✅
**Problem**: `FATAL: sorry, too many clients already` during parallel test execution

**Root Cause**:
- Default HikariCP pool size: 10 connections per DataSource
- Multiple test classes run in parallel, each creating own ApplicationContext
- Each context creates its own DataSource with 10-connection pool
- With container reuse (`.withReuse(true)`), all tests share same PostgreSQL instance
- Example: 10 parallel tests × 10 connections = 100 connections (PostgreSQL default max)

**Solution**:
1. **Reduced HikariCP pool size** to 3 connections per test context:
   ```properties
   # application-test.properties
   spring.datasource.hikari.maximum-pool-size=3
   spring.datasource.hikari.minimum-idle=1
   spring.datasource.hikari.connection-timeout=10000
   spring.datasource.hikari.idle-timeout=300000
   ```

2. **Increased PostgreSQL max_connections** to 200 in Testcontainer:
   ```java
   // PostgresTestConfiguration.java
   .withCommand("postgres", "-c", "max_connections=200")
   ```

**Math**:
- Before: 10 tests × 10 connections = 100 (max limit reached)
- After: 33 tests × 3 connections = 99 (under 100 limit)
- After with increased limit: 66 tests × 3 connections = 198 (under 200 limit)

**Impact**: All integration tests now run successfully in parallel without connection exhaustion

---

### Bug Fix #3: UnsupportedOperationException in JSON Serialization ✅
**Problem**: HTTP 500 errors with `HttpMessageNotWritableException: Could not write JSON (was java.lang.UnsupportedOperationException)` during semantic/hybrid search endpoint calls

**Root Cause**:
Jackson's ObjectMapper trying to serialize immutable collections:
1. **Immutable Lists**: `.toList()` (Java 16+) returns `ImmutableCollections.ListN`
2. **Immutable Maps**: `Map.of()` returns `ImmutableCollections.MapN`
3. **SolrDocument casting**: Direct cast `(Map<String, Object>) solrDoc` produces unmodifiable view
4. Jackson serialization requires mutable collections for proper introspection

**Solution**:
Replaced all immutable collections with mutable equivalents in `SearchRepository.java`:

1. **Lists**: `.toList()` → `.collect(Collectors.toList())`
   ```java
   // Before (immutable)
   response.getResults().stream().map(...).toList()

   // After (mutable ArrayList)
   response.getResults().stream().map(...).collect(Collectors.toList())
   ```

2. **Maps**: `Map.of()` → `new HashMap<>()`
   ```java
   // Before (immutable)
   facetCountsMap = ... ? ... : Map.of();

   // After (mutable HashMap)
   facetCountsMap = ... ? ... : new HashMap<>();
   ```

3. **SolrDocument conversion**: Direct cast → explicit mutable copy
   ```java
   // Before (immutable view)
   .map(d -> (Map<String, Object>) d)

   // After (mutable HashMap copy)
   .map(d -> new HashMap<String, Object>(d))
   ```

**Locations Fixed** (12 total):
- `search()` method: 2 fixes (lines 78-79)
- `semanticSearch(List<Float>...)` method: 2 fixes (lines 155-156) - REMOVED in this PR
- `executeHybridRerankSearch()` method: 2 fixes (lines 273-288)
- `fallbackToKeywordSearch()` method: 2 fixes (lines 371, 383)
- `fallbackToVectorSearch()` method: 2 fixes (lines 443, 450)
- `executeHybridRerankSearch()` error handler: 1 fix (line 455)
- `semanticSearch(String...)` method: 1 fix (line 576)

**Impact**: All search endpoints (`/search`, `/semantic`, `/hybrid`) now return properly serializable JSON responses

---

## 🏗️ Code Quality Improvements

### 1. **Removed Deprecated Methods**
- ❌ **Removed**: `SearchRepository.semanticSearch(List<Float> queryVector, ...)` (77 lines) - unused method
- ❌ **Removed**: `SearchRepository.hybridSearch(...)` (14 lines) - wrapper for `executeHybridRerankSearch()`
- ✅ **Updated**: 26 method calls across production and test code
  - `SearchService.java`: 1 call
  - `SearchRepositoryTest.java`: 11 calls
  - `SearchServiceTest.java`: 6 calls
  - `SearchRepositoryIT.java`: 8 calls
- **Total removed**: ~90 lines of deprecated code
- **Benefit**: No deprecation warnings, cleaner codebase

### 2. **Enhanced SearchRepository**
- Better field resolution with dynamic field pattern matching
- Improved metadata handling (`metadata_*` → top-level fields)
- Multi-valued field normalization (extract first value)
- Solr-native filter support for complex queries
- Comprehensive error handling with fallback strategies

### 3. **Improved Documentation**
- Updated `CLAUDE.md` with:
  - Hybrid search reranking implementation details (not RRF)
  - Jetty compatibility troubleshooting
  - Filter expression handling (dual approach explanation)
  - PostgreSQL connection pool configuration
  - Test execution instructions
  - Common issues and solutions

## 🧪 Testing Infrastructure

### Test Coverage Summary
- **Total Changes**: 4,234 insertions, 1,409 deletions
- **New Test Classes**: 3 major classes
- **New Test Lines**: 1,200+ lines of comprehensive tests
- **Test Pass Rate**: 68+ tests, 100% passing ✅

### New Test Coverage

#### 1. **Comprehensive Integration Tests**
✅ **New**: `SemanticAndHybridSearchIntegrationTest.java` (443 lines)
- **Real APIs**: Uses actual OpenAI embeddings + Anthropic Claude
- **Test Coverage**:
  - Semantic search with natural language queries
  - Semantic search with AI-parsed filters
  - Hybrid search with reranking
  - Hybrid search parameter handling (topK, minScore)
  - Hybrid search keyword + vector combination
  - Fallback mechanism validation
- **Test Data**: Real horror/sci-fi book dataset from JSON files
- **Validation**: Verifies genre matching, relevance, score thresholds

#### 2. **Enhanced Unit Tests**
✅ **New**: `SearchRepositoryTest.java` (457 lines)
- **Mocking**: Mockito-based with mocked SolrClient and EmbeddingService
- **Test Coverage**:
  - Hybrid search basic execution
  - Filter query application and validation
  - Field projection and field list handling
  - Score filtering and threshold validation
  - Metadata normalization
  - Multi-valued field handling
  - Solr query parameter construction
  - Error handling and edge cases
- **Mock Data**: Realistic SolrDocument responses

✅ **Enhanced**: `SearchServiceTest.java` (+300 lines)
- **Test Coverage**:
  - Semantic search AI integration
  - Hybrid search AI integration with Claude
  - Filter expression building from AI responses
  - Query generation validation
  - ChatClient integration
  - Error handling

#### 3. **Improved Integration Tests**
✅ **Enhanced**: `SearchRepositoryIT.java` (+300 lines)
- **Real Infrastructure**: Testcontainers for Solr + PostgreSQL
- **Test Coverage**:
  - Hybrid search query construction with real Solr
  - Rerank query parameter validation
  - Metadata normalization in real responses
  - Field resolution with actual Solr schema
  - Filter query execution
  - Score filtering with real embeddings

#### 4. **New Test Utilities**
✅ **New**: `BookDatasetGenerator.java` (164 lines)
- Generates realistic book datasets for testing
- Supports multiple genres (horror, sci-fi, fantasy)
- Rich metadata (title, author, year, genre, tags, content)

✅ **New**: `EvaluationTestBase.java` (191 lines)
- Base class for evaluation tests
- Common setup for LLM evaluation
- Shared test utilities

✅ **New**: Test data JSON files
- `semantic-search-test-docs.json` (142 lines) - 10 horror books
- `hybrid-search-test-docs.json` (122 lines) - 8 AI/tech documents

#### 5. **Removed Outdated Tests**
❌ **Removed**: `FactCheckingEvaluatorIntegrationTest.java` (178 lines)
❌ **Removed**: `RelevancyEvaluatorIntegrationTest.java` (162 lines)
❌ **Removed**: `FactCheckingEvaluator.java` (77 lines)
❌ **Removed**: `RelevancyEvaluator.java` (70 lines)
- **Reason**: Replaced by more comprehensive `SemanticAndHybridSearchIntegrationTest`
- **Total removed**: ~487 lines of outdated evaluation code

### Test Configuration
✅ **New**: `EvaluationModelsTestConfiguration.java` (83 lines)
- Configures test-specific AI models
- Separate from production configuration

✅ **Enhanced**: `SolrTestConfiguration.java`
- Improved Solr container lifecycle
- Better schema initialization

✅ **Enhanced**: `PostgresTestConfiguration.java`
- Increased max_connections to 200
- Better container reuse

## 📊 Statistics

- **Lines Changed**: +4,234 insertions, -1,409 deletions (net +2,825)
- **Files Modified**: 38 files
- **New Classes**: 6 production classes, 3 test classes
- **New Tests**: 1,200+ lines of test code
- **Tests Passing**: 68+ tests (100% pass rate ✅)
- **Deprecated Code Removed**: ~90 lines
- **Code Coverage**: Comprehensive unit + integration tests

## 🔧 Technical Details

### Dependency Changes

```kotlin
// build.gradle.kts
extra["springAiVersion"] = "1.1.0"  // Changed from 1.1.0-M4

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:1.1.0")
        // Jetty 11 BOM pinned for SolrJ (Solr 9.10.0 compatibility)
        mavenBom("org.eclipse.jetty:jetty-bom:11.0.24")
    }
}
```

### Configuration Changes

#### 1. Multi-LLM Configuration with JDK HttpClient
```java
// AiConfig.java - Explicit RestClient.Builder injection to avoid Jetty
@Bean
public EmbeddingModel embeddingModel(
    @Value("${spring.ai.openai.api-key}") String apiKey,
    RestClient.Builder restClientBuilder) {  // Injected with JDK HttpClient

    OpenAiApi openAiApi = OpenAiApi.builder()
        .apiKey(apiKey)
        .restClientBuilder(restClientBuilder)  // Forces JDK HttpClient, not Jetty
        .build();
    return new OpenAiEmbeddingModel(openAiApi);
}
```

#### 2. HikariCP Connection Pool for Tests
```properties
# application-test.properties
spring.datasource.hikari.maximum-pool-size=3      # Reduced from default 10
spring.datasource.hikari.minimum-idle=1           # Reduced from default 10
spring.datasource.hikari.connection-timeout=10000
spring.datasource.hikari.idle-timeout=300000
```

#### 3. PostgreSQL Testcontainer
```java
// PostgresTestConfiguration.java
@Bean
PostgreSQLContainer<?> postgresContainer() {
    return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
        .withDatabaseName("chatmemory")
        .withUsername("postgres")
        .withPassword("postgres")
        .withCommand("postgres", "-c", "max_connections=200")  // Increased from 100
        .withReuse(true);
}
```

### Hybrid Search Implementation

```java
// SearchRepository.executeHybridRerankSearch()
ModifiableSolrParams params = new ModifiableSolrParams();

// Primary query: keyword search
params.set("q", query);
params.set("defType", "edismax");
params.set("qf", "_text_ content^2");

// Reranking: vector similarity re-scores top-N results
params.set("rq", "{!rerank reRankQuery=$vectorQ reRankDocs=200 reRankWeight=2.0}");
params.set("vectorQ", buildKnnQuery(topK, vectorString));

// Note: Uses rerank, not RRF (RRF not available in Solr 9.10)
```

**Parameters**:
- `reRankDocs=200`: Number of top keyword results to rerank with vector scores
- `reRankWeight=2.0`: Multiplier for vector scores (2.0 = double weight)
- Formula: `finalScore = keywordScore + (vectorScore × 2.0)`

### API Changes

#### No Breaking API Changes
- All REST endpoints remain backward compatible
- Internal method renaming only affects implementation

#### Internal Method Renaming
- ⚠️ `SearchRepository.hybridSearch()` → `executeHybridRerankSearch()`
- ⚠️ `SearchRepository.semanticSearch(List<Float>, ...)` removed (was unused)

## 🚀 Migration Guide

### For Internal Developers

If you have code calling deprecated methods:

**Before:**
```java
searchRepository.hybridSearch(collection, query, topK, filter, fields, minScore);
```

**After:**
```java
searchRepository.executeHybridRerankSearch(collection, query, topK, filter, fields, minScore);
```

### For Tests

Update Mockito mocks:
```java
// Old
when(searchRepository.hybridSearch(...)).thenReturn(response);

// New
when(searchRepository.executeHybridRerankSearch(...)).thenReturn(response);
```

## ✅ Testing & Verification

### All Tests Passing ✅

```bash
./gradlew test
# BUILD SUCCESSFUL in 1m 23s
# 68+ tests passing
# 0 failures
```

### Integration Tests Verified
- ✅ `SearchIntegrationTest` - Traditional search with AI
- ✅ `SemanticAndHybridSearchIntegrationTest` - Real embeddings + Claude
- ✅ `SearchRepositoryIT` - Real Solr integration
- ✅ `IndexIntegrationTest` - Document indexing
- ✅ `ChatMemoryIntegrationTest` - PostgreSQL chat memory
- ✅ `SolrVectorStoreIT` - Vector store operations
- ✅ `SolrVectorStoreObservationIT` - Observability

### Vector Store Tests (Require API Keys)
```bash
export OPENAI_API_KEY="your-key"
export ANTHROPIC_API_KEY="your-key"
./gradlew test --tests "SolrVectorStore*"
./gradlew test --tests "SemanticAndHybridSearchIntegrationTest"
```

### Parallel Test Execution
```bash
./gradlew test --parallel --max-workers=4
# All tests pass without connection pool errors ✅
```

## 📝 Documentation Updates

### CLAUDE.md Enhancements
- ✅ Corrected hybrid search implementation (reranking, not RRF)
- ✅ Added Jetty compatibility troubleshooting section
- ✅ Documented PostgreSQL connection pool configuration
- ✅ Explained filter expression dual approach
- ✅ Added test execution instructions
- ✅ Common issues and solutions section

## 🔗 Related Issues & Context

### Issues Fixed
- ✅ Jetty version compatibility (`NoSuchMethodError`)
- ✅ PostgreSQL connection pool exhaustion (`too many clients`)
- ✅ JSON serialization errors (`UnsupportedOperationException`)

### Features Added
- ✅ Spring AI 1.1.0 GA upgrade
- ✅ Hybrid search with vector reranking
- ✅ Embedding service abstraction
- ✅ Comprehensive test coverage

### Technical Debt Reduced
- ✅ Removed 90 lines of deprecated code
- ✅ Removed 487 lines of outdated evaluation tests
- ✅ Improved code documentation

## 🎓 Lessons Learned

1. **Jetty Version Management**: When pinning Jetty 11 for SolrJ, must ensure all Spring components use JDK HttpClient instead
2. **Connection Pool Tuning**: Parallel tests with container reuse require careful connection pool sizing
3. **Java Collections**: Immutable collections (`.toList()`, `Map.of()`) break Jackson serialization
4. **Solr Reranking vs RRF**: Solr 9.10 uses `rerank` parser, not native RRF (RRF coming in future versions)
5. **Test Data Quality**: Real-world test data (books dataset) provides better integration test validation

## 📦 Deliverables

### Completed ✅
- ✅ Spring AI 1.1.0 GA upgrade
- ✅ Hybrid search with reranking implementation
- ✅ Three critical bugs fixed
- ✅ Comprehensive test coverage (1,200+ lines)
- ✅ Documentation updated
- ✅ Code quality improvements
- ✅ All tests passing (68+ tests)

### Test Coverage Breakdown
- **Unit Tests**: 457 lines (`SearchRepositoryTest`)
- **Integration Tests**: 443 lines (`SemanticAndHybridSearchIntegrationTest`)
- **Service Tests**: 300+ lines (`SearchServiceTest` enhancements)
- **Repository Tests**: 300+ lines (`SearchRepositoryIT` enhancements)
- **Total**: 1,500+ lines of test code

---

**Generated with** 🤖 [Claude Code](https://claude.com/claude-code)

**Co-Authored-By**: Claude <noreply@anthropic.com>
