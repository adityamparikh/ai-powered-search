---
name: work-issue
description: Take a GitHub issue of ai-powered-search (a spec, a bug, or a sub-issue of an epic such as #32) from reading to a green pull request, following the project's ground rules. Use when asked to "work on #N", "implement issue N", "pick up the next sub-issue", or "do W3".
---

# Work an issue end to end

Specs live in GitHub issues on `adityamparikh/ai-powered-search`, not in the tree.

## 1. Read before touching code

```bash
gh issue view <n> -R adityamparikh/ai-powered-search --comments
```

- If the body says "Part of #<epic>" or the issue is listed in an epic, read the epic too
  (`gh issue view 32 ...` for the RAG pipeline). Its **ground rules** apply to every sub-issue and
  its **build order** is binding: do not start a sub-issue whose dependencies are not merged
  (`gh pr list --state merged --search "<dep>"`).
- Note the acceptance criteria, "Failure and rollback" and "Out of scope". Stop at the scope line.
- If a criterion is not checkable, or the issue has no failure/rollback or out-of-scope section,
  say so to the user before building; do not invent the answer silently.

Then read `CLAUDE.md` "Rules and traps" and the `docs/` file its routing table names for the area.

## 2. Build it

- Branch: `git switch -c <type>/<short-name>` (never work on `main`).
- Package by feature, constructor injection, `@Value` properties with defaults,
  `@ConditionalOnProperty` beans.
- New behaviour behind a property that defaults to off (or to today's behaviour); never change an
  existing default. Every optional stage fails safe: WARN and degrade, never a 5xx.
- Tests: unit tests for every new class (happy path, each fallback, edge cases); an IT on real
  Solr/PostgreSQL through Testcontainers (`SolrTestContainers.newSolrContainer()`, project
  configset) for anything that changes what Solr returns. A test that needs a real model key gets
  `@EnabledIfEnvironmentVariable(...)` **and** `@LiveModelTest`.
- With all new flags off, `/ask` must return the same documents in the same order: pin it.
- Docs in the same change: properties and stage behaviour in `docs/rag-pipeline.md`, request flow
  in `docs/architecture.md`, endpoints in `docs/api.md`, user-facing summary in `README.md`, and
  `CLAUDE.md` only if a rule, command or path changed.

## 3. Prove it

```bash
./gradlew spotlessApply
./gradlew verify            # build + checkLicense; must be green
```

New classes need at least 80% line coverage (`build/reports/jacoco/test/html/index.html`).
If the change affects RAG quality, an eval run is part of the proof: use the `run-rag-eval`
skill, which needs the maintainer's go-ahead because it is billed.

## 4. Open the PR (with the user's go-ahead to push)

Use `.github/pull_request_template.md`: `Closes #<n>` on the first line, the title prefixed with the
workstream id for epic work (`W3: ...`), the tests added, the eval output if one was run, and only
the checklist items that are true. Push the branch, never `main`.
