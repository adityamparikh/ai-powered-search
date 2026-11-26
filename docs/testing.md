# Testing

How the test suite is organised, which tests cost money, and how to run each kind. Routed from
CLAUDE.md. How to read and extend the RAG evaluation is in [rag-evaluation.md](rag-evaluation.md).

## The three Gradle test tasks

| Task | Runs | Calls paid APIs? | Needs |
|---|---|---|---|
| `./gradlew test` (and `build`, `verify`) | every test except the tags `live` and `rag-eval` | **Never**, even with real keys exported: the task strips the three model API keys from the test JVM | Docker (Testcontainers) |
| `./gradlew liveTest -PallowBilledCalls=true` | tests tagged `live` (`@LiveModelTest`) | Yes: Anthropic, OpenAI and/or TypeSafe | the class's API key; classes whose key is missing skip |
| `./gradlew ragEval -PallowBilledCalls=true` | `RagEvaluationIT` (tag `rag-eval`) | Yes, ~50 cases | `ANTHROPIC_API_KEY`, `OPENAI_API_KEY` |

`liveTest` and `ragEval` fail before any test starts unless `-PallowBilledCalls=true` is given
(or `ORG_GRADLE_PROJECT_allowBilledCalls=true` is in the environment). **Agents: ask the maintainer
before passing it.** `--tests` filters work with all three tasks.

`BilledTestsAreOptInTest` enforces the split: it scans the test classes and fails the build if one
is enabled by `ANTHROPIC_API_KEY`, `OPENAI_API_KEY` or `TYPESAFE_API_KEY` but carries neither
`@LiveModelTest` nor `@Tag("rag-eval")`. A new test that needs a real model gets both the key
condition (`@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")`) and
`@LiveModelTest`.

## Levels

1. **Unit tests** (`@ExtendWith(MockitoExtension.class)`, `@WebMvcTest`, plain JUnit): for example
   `SearchServiceTest`, `IndexServiceTest`, `SearchControllerTest`, `IndexControllerTest`,
   `RerankingDocumentPostProcessorTest`, `QueryRewriteTest`, `RagMetricsTest`. No containers.
2. **Integration tests, keyless** (`@SpringBootTest` + Testcontainers for Solr, ZooKeeper,
   PostgreSQL, LGTM): `SearchIntegrationTest`, `IndexIntegrationTest`, `SolrHybridSearchIT`,
   `SearchRepositoryIT`, `RagAdvisorOrderingIT`, `ChatMemoryIntegrationTest`,
   `GrafanaLgtmIntegrationTest`, `TypeSafeDecisionsRerankingIT`, `TypeSafeDecisionsScreeningIT`,
   `ApiVersioningTest`. The models are mocked (`MockChatModelConfiguration`, `@MockitoBean`,
   fixed 1536-dimensional vectors) and placeholder keys are set in the test, so these run
   everywhere, including CI.
3. **Live tests** (`@LiveModelTest`, tag `live`): `PromptCacheMetricsAdvisorIT`,
   `QuestionAnswerAdvisorIT`, `RagQuestionAnswerIntegrationTest`,
   `SemanticAndHybridSearchIntegrationTest`, `TypeSafeDecisionsRerankingLiveIT`, `RagEvalFixtureIT`,
   `SolrVectorStoreIT`, `SolrVectorStoreObservationIT`, `SolrVectorStoreDebugTest`. They use real
   embeddings and real Claude/Jev calls. Several have not been run against current models for a
   while, so a red live run is not by itself evidence that a change broke them.
4. **Evaluation** (tag `rag-eval`): `RagEvaluationIT`, with `EvaluationTestBase` (Ollama judge via
   Testcontainers or `-Drag.eval.ollama-url`) and `EvaluationModelsTestConfiguration`. The keyless
   parts are tested by `RagEvalHarnessTest` and `RagMetricsTest`.

## Test configuration

- Shared containers: `SolrTestContainers.newSolrContainer()` (Testcontainers' `SolrContainer`
  cannot start Solr 10 on its own), `SolrTestConfiguration`, `PostgresTestConfiguration`,
  `LgtmTestConfiguration` (image pinned to the `lgtm` service in `docker-compose.yml`).
- `SOLR_TEST_IMAGE=solr:9.11.0` runs the Solr tests against 9.11; CI runs the hybrid tests on both.
- `src/test/resources/application-test.properties` and `application-it.properties` hold
  placeholder keys for the profiles that use them.
- Awaitility waits for Solr commits.

## Commands

```bash
./gradlew test                                                  # all keyless tests
./gradlew test --tests "SearchServiceTest"                      # one class
./gradlew test --tests "dev.aparikh.aipoweredsearch.search.*"   # one package
./gradlew test jacocoTestReport                                 # coverage: build/reports/jacoco/test/html/index.html

# Billed: ask first
./gradlew liveTest -PallowBilledCalls=true --tests "PromptCacheMetricsAdvisorIT" --info
./run-vector-tests.sh                                           # SolrVectorStoreIT + SolrVectorStoreObservationIT via liveTest
./gradlew ragEval -PallowBilledCalls=true -Drag.eval.judge=false
```

Reports: `build/reports/tests/<task>/index.html`, JUnit XML in `build/test-results/<task>/`, the
RAG evaluation report in `build/reports/rag-eval/report.{md,json}`.

## RAG evaluation harness

`RagEvaluationIT` scores `/api/v1/search/ask` on a fixed 71-book fixture
(`src/test/resources/eval/books-fixture.json`) and 50 labelled cases
(`src/test/resources/eval/rag-eval-set.json`) across five categories: follow-up, filter,
vocab-gap, injection and keyword. Per category it reports recall@20 after fusion, recall after
reranking, follow-up parity, context precision, injection pass-through, p50/p95 latency, tokens
per `/ask`, and judge-scored relevance and faithfulness.

- The harness builds its collection from the **project configset** (`solr-config/conf`), not
  Solr's `_default`. In `_default`, `_text_` is never populated, so the BM25 leg silently returns
  nothing.
- Filterable metadata uses explicit typed fields: `metadata_author` (`string`),
  `metadata_price` (`pdouble`) and `metadata_year` (`pint`).
- Options (`-Drag.eval.props`, `-Drag.eval.label`, `-Drag.eval.judge`, `-Drag.eval.ollama-url`,
  `-Drag.eval.cases`) and the flag-flip thresholds are in [rag-evaluation.md](rag-evaluation.md);
  the baseline numbers are in [rag-eval-baseline.md](rag-eval-baseline.md).
- `RetrievalAugmentationAdvisorContractTest` and `RagAdvisorOrderingIT` pin the Spring AI advisor
  behaviour the pipeline relies on (no keys).
