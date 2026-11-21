# Rolling back

How to undo a bad change, cheapest first. There is no deployment pipeline in this repo; the
application runs from `./gradlew bootRun` or the image from `./gradlew bootBuildImage`, against the
services in `docker-compose.yml`. Routed from CLAUDE.md.

Steps that delete data (`docker compose down -v`, deleting a collection, dropping a table) need the
maintainer's go-ahead; see "Ask before running" in CLAUDE.md.

## 1. Turn the stage off (no rebuild)

Every RAG stage added under epic #32 ships behind a property that defaults to off, so a bad stage
is undone by restoring the default and restarting the app. Properties can be set in
`application.properties`, as `-D` system properties, or as environment variables (Spring's relaxed
binding: `SEARCH_RAG_DECISIONS_ENABLED=false`).

| Stage | Property | Off / default |
|---|---|---|
| Decisions screening | `search.rag.decisions.enabled` | `false` |
| Decisions reranker | `search.rag.rerank.provider` | `claude` |
| Reranking altogether | `search.rag.rerank.enabled` | `true` (`false` skips it) |
| Retrieval depth | `search.rag.hybrid.top-k`, `search.rag.rerank.top-k` | `20`, `5` |
| Query-rewrite model | `search.rag.query-rewrite.model` (`RAG_QUERY_REWRITE_MODEL`) | `claude-haiku-4-5` |
| Chat model | `spring.ai.anthropic.chat.model` (`ANTHROPIC_CHAT_MODEL`) | `claude-sonnet-5-5` |
| Prompt caching | `ANTHROPIC_PROMPT_CACHING_ENABLED` | `true` |
| TypeSafe provider | unset `TYPESAFE_API_KEY` | off |

This is a restart, not a runtime kill switch: nothing re-reads these properties while the app runs.

## 2. Revert the code and restart

```bash
git revert <sha>              # or the merge commit: git revert -m 1 <merge-sha>
./gradlew verify              # prove the revert is green before anything else
./gradlew bootRun             # or rebuild the image: ./gradlew bootBuildImage
```

Revert on a branch and merge it through a pull request like any other change; never push to
`main` directly.

## 3. Solr configset changes (`solr-config/conf`)

`init-solr.sh` uploads `solr-config/conf` to ZooKeeper as `ai-powered-search-config` every time
the `solr` service starts, then restarts Solr so existing collections load it. To go back to the
previous schema or `solrconfig.xml`:

```bash
git revert <sha>                         # or: git checkout <good-sha> -- solr-config/conf
docker compose restart solr              # re-uploads the configset and restarts Solr
curl "http://localhost:8983/solr/books/schema/fields/vector"   # check what is live
```

Against a Solr you do not start through docker compose, upload and reload by hand:

```bash
bin/solr zk upconfig -n ai-powered-search-config -d solr-config/conf -z <zk-host>:2181
curl "http://<solr-host>:8983/solr/admin/collections?action=RELOAD&name=books"
```

A rollback that changes how a field is **indexed** (field type, analyzer, `copyField`, vector
dimension) does not rewrite documents already indexed. Re-index them: `POST
/api/v1/index/{collection}/batch` with the source documents (each call re-embeds them through
OpenAI, which is billed). Changes that only affect query time (request handlers, `/hybrid`,
synonyms used at query time) need only the reload.

## 4. PostgreSQL chat memory

The chat-memory table is created by Spring AI (`spring.ai.chat.memory.repository.jdbc.initialize-schema=always`);
there are no application migrations and no down-migrations. If a change corrupts or pollutes
conversation history:

```bash
# Before a risky change: back up
docker exec ai-powered-search-postgres pg_dump -U postgres -d chatmemory > chatmemory-$(date +%F).sql

# Restore that backup (replaces current history; ask first)
docker exec -i ai-powered-search-postgres psql -U postgres -d chatmemory < chatmemory-YYYY-MM-DD.sql
```

History for one conversation can be removed without touching others through Spring AI's
`ChatMemory.clear(conversationId)`; there is no endpoint for it today.

## 5. Dependency upgrades

Revert the version change in `build.gradle.kts` (or the Dependabot PR's merge) and run
`./gradlew verify`, which includes `checkLicense`. Spring AI upgrades can change advisor
behaviour silently; `RetrievalAugmentationAdvisorContractTest` and `RagAdvisorOrderingIT` exist to
catch that, and a RAG quality regression shows up only in `./gradlew ragEval` (billed; ask first)
compared with [rag-eval-baseline.md](rag-eval-baseline.md).

## Last resort: wipe local state

`docker compose down -v` deletes the `solr_data` and `postgres_data` volumes: every indexed
document and every conversation. It is only for a local stack that will not start (for example a
Solr 9 volume under Solr 10.1), and only with the maintainer's go-ahead.
