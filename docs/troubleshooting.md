# Troubleshooting

Known failure messages and their fixes. Routed from CLAUDE.md.

## "Only DenseVectorField is compatible with Vector Query Parsers"
If you see this error: `Error from server at http://localhost:8983/solr: only DenseVectorField is compatible with Vector Query Parsers`

**Cause**: The Solr collection doesn't have the proper vector field configuration.

**Solution**:
1. Restart Docker containers to run the initialization script:
   ```bash
   docker-compose down
   docker-compose up -d
   ```

2. The `init-solr.sh` script will automatically create collections with proper vector field configuration:
   - Field type: `knn_vector_1536` (1536 dimensions)
   - Field name: `vector`
   - Similarity function: cosine
   - Algorithm: HNSW

3. Verify the field configuration:
   ```bash
   curl "http://localhost:8983/solr/books/schema/fields/vector"
   ```

4. If using a custom collection name, ensure it's created with the vector field or modify `init-solr.sh` to add your collection name.

## Vector Store Tests Require API Key
If vector store tests fail with "Cannot invoke EmbeddingResponse.getResults() because embeddingResponse is null":
- Set a valid `OPENAI_API_KEY` environment variable
- Tests use real embeddings, not mocks, so they are `live` tests: run them with
  `./gradlew liveTest -PallowBilledCalls=true` or `./run-vector-tests.sh` (both billed; ask first)

## Jetty HTTP Protocol Violations (no longer applicable)
Earlier versions of this project could fail with "HTTP protocol violation: Authentication challenge without
WWW-Authenticate header" when an invalid OpenAI API key was used, because both SolrJ and Spring AI routed HTTP traffic
through Jetty.

Jetty is no longer on the classpath: SolrJ 10 uses the JDK `HttpClient` and Spring AI 2.x uses the official vendor SDKs.
If OpenAI calls fail, verify that `OPENAI_API_KEY` contains a valid key.

## Test Container Port Conflicts
If Testcontainers fail to start:
- Ensure Docker Desktop is running
- Check for port conflicts with existing Solr/PostgreSQL instances
- Testcontainers uses random ports to avoid conflicts

## `./gradlew check` fails in `spotlessJavaCheck`
The code is formatted with google-java-format and `check` fails on any unformatted line. Run
`./gradlew spotlessApply` and re-run the build. Do not hand-format or disable the check.

## Compile fails with `[NullAway]`
NullAway runs as an error on main sources (`dev.aparikh.aipoweredsearch`, JSpecify mode). Annotate a
value that can be null with `org.jspecify.annotations.@Nullable` (see `AiConfig` for examples) or
prove it non-null; never suppress the check. Test sources have NullAway disabled.

## `liveTest` or `ragEval` fails at once with "makes real, billed calls"
That is the opt-in guard. Billed runs need `-PallowBilledCalls=true`, and an agent needs the
maintainer's go-ahead before passing it.

## `verify` prints "Configuration cache problems" or stores no cache entry
Expected: `checkLicense` comes from a plugin that is not configuration-cache compatible, so any
invocation that includes it runs without the configuration cache. `./gradlew build` keeps it.
