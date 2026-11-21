# CLAUDE.md

Guidance for coding agents working in this repository. This file is a routing table: the rules,
the commands and the map are here; the reference material is in the files listed under "Where to
read more", loaded only when the task needs it.

**ai-powered-search** is a Spring Boot 4.1.1 / Java 25 application (Spring AI 2.0.2, SolrJ 10.1.0)
that does keyword, vector and hybrid search over Apache Solr 10.1 (or 9.11) and answers questions
with RAG (`POST /api/v1/search/ask`). Anthropic Claude generates Solr queries, rewrites follow-ups,
reranks and answers; OpenAI `text-embedding-3-small` (1536 dimensions) embeds; PostgreSQL 16 holds
chat memory; an optional TypeSafe "decisions" provider screens passages.

## Rules and traps

Read these before changing code. Each one has broken something here before, or would.

- **Keep the pass-through `DocumentJoiner`** in `AiConfig.ragChatClient`. Spring AI's default
  `ConcatenationDocumentJoiner` re-sorts by score and undoes Solr's RRF order (hybrid rows carry
  no fused score).
- **Fusion happens in Solr, not in Java.** Hybrid search is one request to the collection's
  `/hybrid` handler (`solr.CombinedQuerySearchHandler`). Do not reintroduce an application-side
  merger; `RrfMerger` was removed on purpose.
- **Use the project configset (`solr-config/conf`)** for any collection a test or the eval harness
  searches by keyword. In Solr's `_default`, `_text_` is never populated, so the BM25 leg silently
  returns nothing. Tests on `_default` must call `SolrTestContainers.registerHybridHandler(...)`.
- **Start Solr in tests with `SolrTestContainers.newSolrContainer()`.** Testcontainers'
  `SolrContainer` 2.0.5 cannot start Solr 10.
- **`searchChatClient` is stateless.** Never add a memory advisor to it; only `ragChatClient`
  keeps memory, keyed by the request's `conversationId`.
- **Advisors run by `getOrder()`, not list position.** `RagAdvisorOrderingIT` pins memory before
  retrieval; keep it green.
- **Post-processors must judge the rewrite.** Wrap any new RAG post-processor with
  `AiConfig.judgedAgainstTheRewrite(...)`; `RetrievalAugmentationAdvisor` hands them the original
  follow-up.
- **New RAG stages ship behind a property that defaults to off**, and fail safe: a timeout, parse
  error or outage degrades to the previous behaviour with a `WARN`, never a 5xx.
- **`TYPESAFE_API_KEY` defaults to the string `false`**, not empty
  (`spring.ai.typesafe.api-key=${TYPESAFE_API_KEY:false}`). An empty key fails startup.
- **Formatting is enforced.** `check` runs `spotlessCheck` (google-java-format over `src/**/*.java`)
  and fails on unformatted code. Run `./gradlew spotlessApply`; never hand-format or disable it.
- **NullAway is a compile error** on main sources (JSpecify mode). Use
  `org.jspecify.annotations.@Nullable`; never suppress it. It is off for test sources.
- **A test that calls a paid API gets `@LiveModelTest`** as well as its `@EnabledIfEnvironmentVariable`
  key check. `BilledTestsAreOptInTest` fails the build otherwise.
- **The configuration class is `AiConfig`** (not `SpringAiConfig`). Path versioning: controllers map
  `/api/{version}/...` with `version = "v1"`; `ApiVersioningTest` pins it.
- **Docs move with the code.** Update CLAUDE.md, README and `docs/` (diagrams in
  `docs/architecture.md`) in the same PR when commands, configuration or a request path change.

## Ask before running

Never do these without the maintainer's explicit go-ahead in this session:

- `./gradlew ragEval` or `./gradlew liveTest` (they refuse to start without
  `-PallowBilledCalls=true`; do not add the flag on your own), `./run-vector-tests.sh`, and any
  other command that sends real requests to Anthropic, OpenAI or TypeSafe, including `bootRun`
  traffic or `POST /api/v1/index/...` with real keys (indexing embeds every document).
- `docker compose down -v`, deleting a Solr collection or configset, or dropping/truncating the
  PostgreSQL chat-memory table: these destroy indexed documents and conversations.
- `git push` to `main`, force-pushes, merging a PR, or editing GitHub settings, labels or issues.
  Work on a branch and open a pull request.
- Commenting `@claude` on an issue or PR, or anything else that triggers the Claude workflows
  (`.github/workflows/claude*.yml`); each run is billed and `claude.yml` can push commits.
- Adding a dependency under a licence not in `config/allowed-licenses.json`, or editing that file.

## Commands

```bash
./gradlew verify            # THE pre-push command: build + checkLicense (what CI runs)
./gradlew spotlessApply     # fix formatting; run it before verify/check
./gradlew build             # compile (Error Prone + NullAway), spotlessCheck, test, JaCoCo, jar
./gradlew check             # build minus the jar
./gradlew test              # every keyless test; never calls a paid API, even with keys exported
./gradlew test --tests "SearchServiceTest"                     # one class
./gradlew test --tests "dev.aparikh.aipoweredsearch.search.*"  # one package
./gradlew checkLicense      # dependency licences vs config/allowed-licenses.json (not in check)
./gradlew test jacocoTestReport   # coverage: build/reports/jacoco/test/html/index.html
./gradlew bootRun           # needs ANTHROPIC_API_KEY, OPENAI_API_KEY and docker compose services
./gradlew bootBuildImage    # OCI image

# Billed: ask first (see above)
./gradlew liveTest -PallowBilledCalls=true [--tests ...]   # tests tagged live (@LiveModelTest)
./gradlew ragEval  -PallowBilledCalls=true [-Drag.eval.*]  # RAG evaluation harness

docker compose up -d        # Solr 10.1 + ZooKeeper, PostgreSQL 16, Grafana LGTM
docker compose ps; docker compose logs solr
```

Tests need a running Docker daemon (Testcontainers). `verify` runs without the configuration cache
because the licence plugin does not support it; `build` keeps it. A failed build prints the test
and line; JUnit XML is in `build/test-results/test/`, HTML in `build/reports/tests/test/`.

## Repository layout

| Path | What it is |
|---|---|
| `src/main/java/dev/aparikh/aipoweredsearch/` | The application, package by feature (below) |
| `src/main/resources/application.properties` | All configuration and its env-var defaults; `prompts/*.st` are the query-generation system prompts |
| `src/test/java/...` | Tests, same packages, plus `evaluation/` (RAG harness), `fixtures/` (book dataset generator), `LiveModelTest`, `BilledTestsAreOptInTest` |
| `src/test/resources/eval/` | RAG eval fixture (71 books) and the 50 labelled cases |
| `src/test/resources/test-data/` | JSON documents the search ITs index |
| `solr-config/conf/` | The project Solr configset: `managed-schema.xml` (vector field, `metadata_*`, `copyField` to `_text_`), `solrconfig.xml` (`/hybrid` handler), synonyms |
| `init-solr.sh` | Run by the `solr` service: uploads the configset, creates `books`, restarts Solr |
| `docker-compose.yml` | Local Solr, ZooKeeper, PostgreSQL, LGTM; reads `POSTGRES_*` from `.env` |
| `grafana/` | `dashboards/*.json` (4 dashboards), `provisioning/dashboards/`, `provisioning/alerting/` (alert rules) |
| `build.gradle.kts` | Dependencies and versions, Error Prone/NullAway, Spotless, the `test`/`liveTest`/`ragEval`/`verify` tasks, licence report; `gradle.properties` turns on the build and configuration caches; `gradle/wrapper/` pins Gradle 9.8.2 |
| `config/allowed-licenses.json` | Licences `checkLicense` accepts; `LICENSE` and `NOTICE` are the project's Apache 2.0 licence |
| `.github/workflows/` | `ci.yml` (`verify` on every PR, dependency graph), `hybrid-search-solr.yml` (Solr 10.1/9.11 matrix), `claude-code-review.yml` (AI review on PRs), `claude.yml` and `claude-pr-review.yml` (`@claude`); the three Claude workflows skip, with a notice, when their secret (`CLAUDE_CODE_OAUTH_TOKEN` or `ANTHROPIC_API_KEY`) is not set |
| `.github/ISSUE_TEMPLATE/`, `.github/pull_request_template.md` | Spec template and the PR review checklist |
| `.claude/skills/` | Project skills for the repeated jobs (see "Skills") |
| `.env.example` | Every env var the app and compose read; copy to `.env` (git-ignored) |
| `run-vector-tests.sh` | Runs the vector store live tests (billed) |
| `schema-books.json` | A Solr schema API dump kept for reference; nothing reads it |
| `.junie/guidelines.md` | Generic Spring Boot advice for JetBrains Junie; not project rules |

Docs, and whether to trust them:

| Status | Files |
|---|---|
| **Current**, kept in step with the code | `CLAUDE.md`, `README.md`, everything in `docs/` except `docs/archive/`, `SOLR_ENHANCEMENTS.md`, `grafana/README.md` |
| Secondary reference, not reviewed with every change; code and `docs/` win on conflict | `QUICK_REFERENCE.md`, `VECTOR_SEARCH_QUICK_REFERENCE.md`, `SOLR-SETUP.md`, `CHAT_MEMORY.md` |
| **Historical**, do not follow | `docs/archive/*` (client-side RRF era; see `docs/archive/README.md`) |

Main packages (`dev.aparikh.aipoweredsearch`):

- `search/`: `SearchController` (`/api/v1/search/...`), `SearchService` (Claude query generation,
  `ask()`), `SearchRepository` (Solr queries, `executeHybridRerankSearch()` and its fallbacks),
  `HybridDocumentRetriever` (RAG retrieval), `RerankingDocumentPostProcessor` (Claude reranker), `model/`.
- `indexing/`: `IndexController` (`/api/v1/index/...`), `IndexService`, `model/`.
- `solr/`: `SolrQueryUtils`; `solr/vectorstore/`: `SolrVectorStore` (Spring AI `VectorStore`),
  `SolrVectorStoreOptions`, `VectorStoreFactory`, `VectorStoreConfig`.
- `embedding/`: `EmbeddingService`, `VectorFormatUtils`.
- `config/`: `AiConfig` (chat clients, RAG advisor chain, query rewrite, embedding model),
  `TypeSafeDecisionsConfig`, `PromptCacheMetricsAdvisor`, `SolrConfig`,
  `SolrConfigurationProperties`, `RestClientConfig`, `OpenApiConfig`.

## Tools an agent needs

- **`gh`**: specs, epics and acceptance criteria live in GitHub issues, not in the tree. Read them
  with `gh issue view <n> -R adityamparikh/ai-powered-search` (epic #32 is the RAG pipeline and its
  ground rules; #87 is the roadmap); `gh pr view`, `gh pr checks` for CI. Authenticate with
  `gh auth login`. Reading is fine; writing needs a go-ahead (see above).
- **`docker compose`** for the local services; **Docker** for Testcontainers.
- **`curl` against Solr**: `curl "http://localhost:8983/solr/books/schema/fields/vector"`,
  `curl "http://localhost:8983/solr/admin/collections?action=LIST"`.
- **`psql`** through the container: `docker exec -it ai-powered-search-postgres psql -U postgres -d chatmemory`.
- **Telemetry**: Prometheus `localhost:9090`, Loki `localhost:3100`, Tempo `localhost:3200`,
  Grafana `localhost:3000` (no login locally). Query recipes are in `docs/observability.md`.
- **App**: `http://localhost:8080/swagger-ui.html`, `/api-docs`, `/actuator/health`.

## Environment

`.env.example` lists every variable with its default. Required for `bootRun`: `ANTHROPIC_API_KEY`,
`OPENAI_API_KEY`. Optional: `TYPESAFE_API_KEY`, `ANTHROPIC_CHAT_MODEL` (`claude-sonnet-5-5`),
`RAG_QUERY_REWRITE_MODEL` (`claude-haiku-4-5`), `ANTHROPIC_PROMPT_CACHING_*`, `POSTGRES_USER` /
`POSTGRES_PASSWORD` (`postgres`), `OTEL_EXPORTER_OTLP_ENDPOINT`. Tests need none of them: the keyless
suite sets placeholder keys itself, and CI runs it with no keys at all.

Keys an agent can see can be exfiltrated by a prompt injection. Use an Anthropic key from a
dedicated workspace with a spend limit and a project-scoped OpenAI key, never account-wide keys.
In GitHub Actions, `claude.yml` holds `contents: write`; `id-token: write` in the Claude workflows
is what `anthropics/claude-code-action` uses to obtain its GitHub App token.

## Working on an issue

1. Read the issue and, for an epic sub-issue, the epic's ground rules (`gh issue view 32`). Respect
   its build order and "Out of scope".
2. Branch, change, add tests that assert behaviour (real Solr through Testcontainers where it
   matters, not just `verify(mock)`), update the docs the change touches.
3. `./gradlew spotlessApply && ./gradlew verify` until green.
4. Open a PR from the template; the body says `Closes #<issue>`, and every checklist item is true.
   New specs use the **Spec** issue template, which asks for failure and rollback behaviour and
   non-goals.

## Skills

`.claude/skills/` holds the repeated jobs: `work-issue` (an issue or epic sub-issue end to end),
`run-rag-eval` (measure a RAG change against the baseline; billed, ask first), `upgrade-dependency`
(bump a version, check licences and the advisor contract tests), `add-rag-stage` (a new
flag-guarded, fail-safe RAG stage), `fix-sonar-findings` (list Sonar issues through its web API and
fix them without changing behaviour; needs `SONAR_HOST_URL` and `SONAR_TOKEN`).

## Where to read more

| File | Read it when |
|---|---|
| `docs/architecture.md` | You need the request flow of any endpoint (Mermaid component and sequence diagrams) |
| `docs/api.md` | You touch an endpoint, its payloads or API versioning |
| `docs/hybrid-search.md` | You touch `SearchRepository`, the vector store, the Solr schema or filter handling |
| `docs/rag-pipeline.md` | You touch `/ask`: retrieval, query rewrite, decisions, reranking, chat memory, their properties |
| `docs/prompt-caching.md` | You touch Anthropic chat options or `PromptCacheMetricsAdvisor` |
| `docs/testing.md` | You add tests, need the live/keyless split, or a test infra helper |
| `docs/rag-evaluation.md` | You run or extend the RAG eval, or decide whether to flip a stage flag (thresholds) |
| `docs/rag-eval-baseline.md` | You compare an eval run with the baseline numbers |
| `docs/observability.md` | You need metrics, logs, traces or alerts from a running app |
| `docs/rollback.md` | A change must be undone: flags, revert, Solr configset, chat memory |
| `docs/troubleshooting.md` | You hit a known error (vector field, formatting, NullAway, billed-call guard) |
| `SOLR_ENHANCEMENTS.md` | You change highlighting, facets, spellcheck, boosting or synonyms |
