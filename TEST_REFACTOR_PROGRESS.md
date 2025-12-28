# Test Refactor Progress Report

## Summary
This document tracks the comprehensive test refactor for the AI-powered search application, implementing proper unit tests, integration tests with RRF, and evaluation tests using real LLM models.

## ✅ Completed Work

### 1. Reverted Incorrect Evaluation Test Changes
- **Action**: Used `git checkout` to restore all evaluation test files to original state
- **Location**: `src/test/java/dev/aparikh/aipoweredsearch/evaluation/`
- **Result**: All evaluation tests now have strict assertions that validate search quality

### 2. Upgraded Solr to 9.10.0
Updated all test containers to use Solr 9.10.0 (with native RRF support):
- `src/test/java/dev/aparikh/aipoweredsearch/search/SolrTestBase.java` - Line 22
- `src/test/java/dev/aparikh/aipoweredsearch/config/SolrTestConfiguration.java` - Line 18
- `src/test/java/dev/aparikh/aipoweredsearch/solr/vectorstore/SolrVectorStoreObservationIT.java` - Line 42
- `src/test/java/dev/aparikh/aipoweredsearch/solr/vectorstore/SolrVectorStoreIT.java` - Line 37

### 3. Implemented Native RRF in SearchRepository
**File**: `src/main/java/dev/aparikh/aipoweredsearch/search/SearchRepository.java`

**Changes**:
- Added import: `org.apache.solr.common.params.ModifiableSolrParams`
- Replaced `SolrQuery` with `ModifiableSolrParams` for proper RRF configuration
- Implemented native Solr 9.10.0 RRF:
  ```java
  params.set("rrf", "true");
  params.set("rrf.queryFields", "q,vectorQ");
  params.set("rrf.k", "60");
  ```
- Uses rerank query for vector search: `{!rerank reRankQuery=$vectorQ reRankDocs=200 reRankWeight=1}`
- Keyword search with edismax: `defType=edismax`, `qf=_text_`

**Formula**: RRF score = sum(1 / (k + rank)) where k=60

### 4. Created Book Dataset Generator
**File**: `src/test/java/dev/aparikh/aipoweredsearch/fixtures/BookDatasetGenerator.java`

**Features**:
- Generates exactly 1000 books
- 100 genres with 10 books each
- Realistic metadata: title, author, description, genre, year, ISBN, pages, rating, publisher
- Fixed random seed (42) for reproducibility
- `toMap()` method for easy Solr indexing

**Usage**:
```java
List<BookDatasetGenerator.Book> books = BookDatasetGenerator.generate1000Books();
```

### 5. Set Up Reusable Testcontainers with @RestartScope
**File**: `src/test/java/dev/aparikh/aipoweredsearch/config/EvaluationModelsTestConfiguration.java`

**Configuration**:
```java
@Bean
@RestartScope
public OllamaContainer ollamaContainer() {
    return new OllamaContainer(DockerImageName.parse("ollama/ollama:latest"))
            .withReuse(true);
}

@Bean
@RestartScope
public SolrContainer solrContainer() {
    return new SolrContainer(DockerImageName.parse("solr:9.10.0"))
            .withEnv("SOLR_HEAP", "512m")
            .withReuse(true);
}
```

**Model Configuration**:
- Model: `bespoke-minicheck` (constant: `BESPOKE_MINICHECK`)
- Settings for OllamaChatOptions:
  - `numPredict(2)` - Limit tokens for yes/no answers
  - `temperature(0.0d)` - Deterministic output

## 🔄 Remaining Work

### 6. Create Comprehensive Unit Tests (PENDING)

#### 6.1 SearchServiceTest.java
**Location**: `src/test/java/dev/aparikh/aipoweredsearch/search/SearchServiceTest.java`

**Required Tests**:
```java
@ExtendWith(MockitoExtension.class)
class SearchServiceTest {
    @Mock ChatClient chatClient;
    @Mock SearchRepository searchRepository;
    @Mock EmbeddingService embeddingService;
    @InjectMocks SearchService searchService;

    @Test
    void shouldGenerateSolrQueryFromNaturalLanguage() {
        // Mock ChatClient to return structured Solr query
        // Verify LLM prompt contains schema info
        // Verify service calls repository with correct params
    }

    @Test
    void shouldHandleEmptySearchResults() { }

    @Test
    void shouldHandleLLMErrors() { }
}
```

#### 6.2 SearchRepositoryTest.java
**Location**: `src/test/java/dev/aparikh/aipoweredsearch/search/SearchRepositoryTest.java`

**Required Tests**:
```java
class SearchRepositoryTest {
    @Test
    void shouldBuildCorrectKNNQuery() {
        // Test SolrQueryUtils.buildKnnQuery()
    }

    @Test
    void shouldBuildCorrectRRFParams() {
        // Verify RRF parameters
        // Check rrf=true, rrf.queryFields, rrf.k
    }

    @Test
    void shouldIncludeScoreInFieldList() { }
}
```

#### 6.3 IndexServiceTest.java
**Location**: `src/test/java/dev/aparikh/aipoweredsearch/indexing/IndexServiceTest.java`

#### 6.4 EmbeddingServiceTest.java
**Location**: `src/test/java/dev/aparikh/aipoweredsearch/embedding/EmbeddingServiceTest.java`

**Target**: 80% line coverage across all service and repository classes

### 7. Update Integration Tests with RRF and Book Data (PENDING)

#### 7.1 SearchRepositoryIT.java
**File**: `src/test/java/dev/aparikh/aipoweredsearch/search/SearchRepositoryIT.java`

**Required Changes**:
1. Load book dataset in `@BeforeEach`:
   ```java
   @BeforeEach
   void setUp() throws Exception {
       // Create collection with vector fields
       // Load 1000 books from BookDatasetGenerator
       // Generate embeddings for each book
       // Index into Solr
   }
   ```

2. Update hybrid search tests to verify RRF:
   ```java
   @Test
   void shouldPerformHybridSearchWithRRF() {
       // Execute hybrid search
       // Verify RRF combines keyword + semantic results
       // Check scores are RRF-based, not just vector similarity
   }
   ```

3. Add book-specific test cases:
   ```java
   @Test
   void shouldFindBooksByGenre() { }

   @Test
   void shouldFindBooksByAuthor() { }

   @Test
   void shouldRankBooksByRRF() {
       // Query: "science fiction space adventure"
       // Verify top results match both keywords AND semantic similarity
   }
   ```

### 8. Fix All Evaluation Tests (PENDING - CRITICAL)

All 6 evaluation test files need updates:

#### Common Pattern for All Evaluation Tests:

```java
@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration",
    "spring.ai.openai.api-key=test-key",
    "spring.ai.anthropic.api-key=test-key"
})
@Import(EvaluationModelsTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class SomeEvaluationIT {

    @Autowired
    private OllamaContainer ollama;

    @Autowired
    private SolrContainer solr;

    private FactCheckingEvaluator factCheckingEvaluator;
    private RelevancyEvaluator relevancyEvaluator;

    private static final String BOOKS_COLLECTION = "books";

    @BeforeEach
    void setUp() throws Exception {
        // 1. Pull bespoke-minicheck model
        ollama.execInContainer("ollama", "pull", BESPOKE_MINICHECK);

        // 2. Create Ollama API with JDK HttpClient
        String baseUrl = ollama.getEndpoint();
        RestClient.Builder restClientBuilder = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(new JdkClientHttpRequestFactory());

        OllamaApi ollamaApi = OllamaApi.builder()
            .baseUrl(baseUrl)
            .restClientBuilder(restClientBuilder)
            .build();

        // 3. Create ChatModel with user-specified config
        OllamaChatModel chatModel = new OllamaChatModel(
            ollamaApi,
            OllamaChatOptions.builder()
                .model(BESPOKE_MINICHECK)
                .numPredict(2)
                .temperature(0.0d)
                .build()
        );

        // 4. Create evaluators
        factCheckingEvaluator = FactCheckingEvaluator.builder(ChatClient.builder(chatModel)).build();
        relevancyEvaluator = RelevancyEvaluator.builder(ChatClient.builder(chatModel)).build();

        // 5. Create Solr collection with vector fields
        createBooksCollection(solr);

        // 6. Load 1000 books
        loadBooks(solr, BOOKS_COLLECTION);
    }

    private void createBooksCollection(SolrContainer solr) throws Exception {
        // Create collection via Solr API
        // Add vector field type (knn_vector_1536)
        // Add vector field
    }

    private void loadBooks(SolrContainer solr, String collection) throws Exception {
        List<BookDatasetGenerator.Book> books = BookDatasetGenerator.generate1000Books();

        // For each book:
        // 1. Generate embedding from description
        // 2. Create SolrInputDocument
        // 3. Add all fields including vector
        // 4. Index to Solr

        // Commit
    }
}
```

#### 8.1 HybridSearchFactCheckingEvaluationIT.java
**Tests to Update**:
- Replace hardcoded facts with book-based queries
- Example: "Find books about Java programming published after 2010"
- Verify evaluator confirms results are factually accurate

#### 8.2 HybridSearchRelevancyEvaluationIT.java
**Tests to Update**:
- Test RRF relevancy vs pure keyword or pure semantic
- Example: "science fiction space exploration" should rank books matching both higher

#### 8.3 KeywordSearchFactCheckingEvaluationIT.java
**Tests to Update**:
- Test LLM-generated Solr queries are factually correct
- Verify genre filters, date ranges work correctly

#### 8.4 KeywordSearchRelevancyEvaluationIT.java
**Tests to Update**:
- Test LLM generates relevant Solr queries from natural language
- Verify faceting, sorting, filtering produce relevant results

#### 8.5 SemanticSearchFactCheckingEvaluationIT.java
**Tests to Update**:
- Test vector search returns factually accurate books
- Example: Query about "artificial intelligence" should return AI books, not just keyword matches

#### 8.6 SemanticSearchRelevancyEvaluationIT.java
**Tests to Update**:
- Test semantic similarity produces relevant results
- Verify cosine similarity threshold filters work correctly

### 9. Verify 80%+ Test Coverage (PENDING)

**Commands**:
```bash
./gradlew test jacocoTestReport
open build/reports/jacoco/test/html/index.html
```

**Target Coverage**:
- SearchRepository: 85%+
- SearchService: 85%+
- IndexService: 80%+
- EmbeddingService: 80%+
- Overall project: 80%+

## Key Technical Decisions

### RRF Implementation
- Uses Solr 9.10.0 native RRF (not custom Java implementation)
- RRF constant k=60 (standard value)
- Combines keyword (edismax) + vector (KNN) searches
- Formula: score = sum(1 / (k + rank))

### Evaluation Model Configuration
- Model: bespoke-minicheck (specialized for fact-checking)
- numPredict: 2 (limits to yes/no type answers)
- temperature: 0.0 (deterministic, reproducible results)

### Test Data Strategy
- 1000 synthetic books (not real Amazon data to avoid API dependencies)
- 100 genres × 10 books each
- Realistic metadata for comprehensive testing
- Fixed random seed for reproducibility

### Container Reuse Strategy
- @RestartScope on Solr and Ollama containers
- .withReuse(true) for Testcontainers
- Significantly improves test execution speed
- Maintains isolation through proper cleanup

## Next Session Action Items

1. **Priority 1**: Implement evaluation test base class with book dataset loading
2. **Priority 2**: Update one evaluation test file as a template (HybridSearchFactCheckingEvaluationIT)
3. **Priority 3**: Apply template to remaining 5 evaluation test files
4. **Priority 4**: Create unit tests (SearchServiceTest, SearchRepositoryTest)
5. **Priority 5**: Update integration tests with book data
6. **Priority 6**: Verify coverage and add missing tests

## Files Modified

### Main Source Code
- `src/main/java/dev/aparikh/aipoweredsearch/search/SearchRepository.java` - Native RRF implementation

### Test Infrastructure
- `src/test/java/dev/aparikh/aipoweredsearch/config/EvaluationModelsTestConfiguration.java` - Reusable containers
- `src/test/java/dev/aparikh/aipoweredsearch/fixtures/BookDatasetGenerator.java` - Test data generator
- `src/test/java/dev/aparikh/aipoweredsearch/search/SolrTestBase.java` - Solr 9.10.0
- `src/test/java/dev/aparikh/aipoweredsearch/config/SolrTestConfiguration.java` - Solr 9.10.0
- `src/test/java/dev/aparikh/aipoweredsearch/solr/vectorstore/SolrVectorStoreObservationIT.java` - Solr 9.10.0
- `src/test/java/dev/aparikh/aipoweredsearch/solr/vectorstore/SolrVectorStoreIT.java` - Solr 9.10.0

### Evaluation Tests (Restored to Original)
- All files in `src/test/java/dev/aparikh/aipoweredsearch/evaluation/` reverted to use strict assertions

## Testing Commands

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "HybridSearchFactCheckingEvaluationIT"

# Run with coverage
./gradlew test jacocoTestReport

# Run only unit tests (fast)
./gradlew test --tests "*Test"

# Run only integration tests
./gradlew test --tests "*IT"

# Run only evaluation tests (slow, requires Docker)
./gradlew test --tests "*Evaluation*"
```

## Known Issues / Considerations

1. **Ollama Model Pull Time**: First test run will be slow as bespoke-minicheck model needs to be pulled
2. **Docker Required**: All tests now require Docker (Testcontainers)
3. **Memory Requirements**: Solr + Ollama containers need adequate Docker memory (recommend 4GB+)
4. **Test Execution Time**: Evaluation tests will be slow (~1-2 min per test with LLM calls)
5. **Model Reliability**: bespoke-minicheck may still have false negatives/positives - may need model tuning

## References

- Solr 9.10.0 RRF Documentation: https://solr.apache.org/guide/solr/latest/query-guide/query-re-ranking.html
- Spring AI Evaluation: https://docs.spring.io/spring-ai/reference/api/testing.html
- Testcontainers Reuse: https://java.testcontainers.org/features/reuse/
