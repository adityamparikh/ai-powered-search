# GitHub settings and issue edits still to apply

The `aine-readiness` branch fixes everything that lives in the repository. The items below can
only be changed on GitHub, by the maintainer. None of them has been applied. Each one names the
AINE readiness item it closes, and the exact command. Run them after this branch is merged, so
the `Build and verify` check from `.github/workflows/ci.yml` exists on `main`.

Repository: `adityamparikh/ai-powered-search`. All commands need `gh auth login` as an admin.

## 1. Protect `main` (closes: human in the way of changes to `main`; review rules)

Today `gh api repos/adityamparikh/ai-powered-search/branches/main/protection` returns 404 "Branch
not protected", while `.github/workflows/claude.yml` gives `@claude` runs `contents: write` and
`Bash(git:*)`. This requires a pull request with green CI for every change to `main`:

```bash
gh api -X PUT repos/adityamparikh/ai-powered-search/branches/main/protection \
  -H "Accept: application/vnd.github+json" --input - <<'JSON'
{
  "required_status_checks": {
    "strict": true,
    "contexts": ["Build and verify", "Solr 10.1.0", "Solr 9.11.0"]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "required_approving_review_count": 0,
    "dismiss_stale_reviews": true
  },
  "restrictions": null,
  "required_linear_history": false,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_conversation_resolution": true
}
JSON
```

Check: `gh api repos/adityamparikh/ai-powered-search/branches/main/protection --jq '.required_status_checks.contexts, .enforce_admins.enabled'`.

Decisions for the maintainer:

- `enforce_admins: true` makes the owner go through a PR as well. That is what stops a local agent
  running with the owner's credentials from pushing to `main`. Set it to `false` to keep a direct
  push for yourself.
- `required_approving_review_count: 0` requires a PR without blocking a solo maintainer, who cannot
  approve their own PR. Raise it to `1` once there is a second reviewer.

## 2. Gate billed `@claude` runs behind an environment (closes: human in the way of spending money)

`claude.yml` and `claude-pr-review.yml` run on any `@claude` comment from a user with write access,
using `CLAUDE_CODE_OAUTH_TOKEN` and `ANTHROPIC_API_KEY`. To make each run wait for an approval:

```bash
# Create the environment with the maintainer as required reviewer
OWNER_ID=$(gh api users/adityamparikh --jq .id)
gh api -X PUT repos/adityamparikh/ai-powered-search/environments/claude \
  --input - <<JSON
{ "reviewers": [ { "type": "User", "id": ${OWNER_ID} } ], "deployment_branch_policy": null }
JSON

# Move the secrets from repository scope into the environment
gh secret set CLAUDE_CODE_OAUTH_TOKEN --env claude -R adityamparikh/ai-powered-search
gh secret set ANTHROPIC_API_KEY       --env claude -R adityamparikh/ai-powered-search
```

Then add `environment: claude` under `jobs.claude` in `.github/workflows/claude.yml` and
`.github/workflows/claude-pr-review.yml` (and under `jobs.claude-review` in
`claude-code-review.yml` if PR reviews should wait too), and delete the repository-level copies:
`gh secret delete ANTHROPIC_API_KEY -R adityamparikh/ai-powered-search`. The workflow edit is left
to you because it makes every `@claude` run wait for a click.

Decision for the maintainer: `claude.yml` and `claude-pr-review.yml` have identical triggers, so
every `@claude` comment starts two billed runs (one on the OAuth token, one on the API key).
Deleting `claude-pr-review.yml` halves that.

## 3. Scope the keys an agent can reach (closes: blast radius of a prompt injection)

Not a GitHub setting for the local half, but listed here because it is a change only the
maintainer can make:

- Create an Anthropic workspace for this project with a monthly spend limit, issue a key there,
  and use it in `.env` and in CI: `gh secret set ANTHROPIC_API_KEY --env claude -R adityamparikh/ai-powered-search`.
- Create an OpenAI project with a budget and a project key; use that as `OPENAI_API_KEY`.
- Revoke the account-wide keys currently exported in the developer shell.

## 4. Secret scanning extras (optional; secret scanning and push protection are already on)

```bash
gh api -X PATCH repos/adityamparikh/ai-powered-search --input - <<'JSON'
{ "security_and_analysis": {
    "secret_scanning_non_provider_patterns": { "status": "enabled" },
    "secret_scanning_validity_checks": { "status": "enabled" } } }
JSON
```

## 5. Add failure/rollback and non-goals to the newest specs (closes: spec failure paths; spec non-goals)

The new `.github/ISSUE_TEMPLATE/spec.md` asks for both sections, but the newest open specs, #97 and
#96, predate it. The sections below are **drafts for the maintainer to review**, written from the
issue bodies and the code at `a89360e`; correct them before posting. Each command appends to the
existing body.

### #97 (hybrid filter clauses joined with a bare AND)

```bash
gh issue view 97 -R adityamparikh/ai-powered-search --json body -q .body > /tmp/97.md
cat >> /tmp/97.md <<'MD'

## Failure and rollback
- **Solr rejects a clause** (syntax error, unknown field): the combined `/hybrid` request fails and
  `executeHybridRerankSearch` falls back to keyword-only, then vector-only, as today; each fallback
  applies the same clauses as separate `fq` parameters. A bad clause fails there too, and
  `fallbackSearch` then returns an empty result with ERROR logs (today's behaviour); it must never
  retry without the filter, which would return documents the filter excludes. Test this with one
  invalid clause.
- **An empty clause list** sends no `filter` / `fq` at all (no `*:*` placeholder).
- **Rollback:** revert the PR. No schema, configset or data change is involved; the signature change
  (`String filterExpression` -> `List<String> filterQueries`) is internal to `SearchService`,
  `SearchRepository` and `HybridDocumentRetriever`.

## Out of scope
- Changing how Claude generates filter clauses, or validating their syntax before sending them.
- Passing filters from `/ask` to retrieval (today it passes none).
- The `SolrVectorStore` filter-expression path (Spring AI syntax), which is separate.
MD
gh issue edit 97 -R adityamparikh/ai-powered-search --body-file /tmp/97.md
```

### #96 (chunk long documents)

```bash
gh issue view 96 -R adityamparikh/ai-powered-search --json body -q .body > /tmp/96.md
cat >> /tmp/96.md <<'MD'

## Failure and rollback
- **Embedding a chunk fails** (rate limit, a chunk over the model limit): the document is not
  written at all; the old version and its chunks stay in place, and the index call reports which
  document failed. A batch reports per-document results instead of failing as a whole.
- **Re-indexing is delete-then-add of the document block in one update request**, so a failure
  between the two cannot leave orphan chunks or a parent without children (test it).
- **Splitter or tokenizer unavailable:** indexing fails loudly at startup, not per request.
- **Rollback:** behind a property (for example `search.indexing.chunking.enabled`, default off), so
  turning it off stops new chunked writes. Documents already chunked need a re-index to return to one
  vector per document: delete the children (`{!child of="*:* -_nest_path_:*"}` on layout A, or by
  parent-ID field on layout B) and re-index the parents. Re-embedding is billed. Record the
  collection's pre-change document count to verify the rollback.

## Out of scope
- File parsing and connectors (#86).
- Delete and retention semantics (#77), beyond replacing a document's own chunks.
- Choosing or changing the embedding model (#82).
- Per-collection settings other than chunk size and overlap (#70).
MD
gh issue edit 96 -R adityamparikh/ai-powered-search --body-file /tmp/96.md
```

## 6. Make PRs trace to issues (closes, over time: shipped work traceable to a spec)

Nothing to set: `.github/pull_request_template.md` now opens with `Closes #`, and CLAUDE.md and the
`work-issue` skill require it. The audit item passes once the next few merged PRs carry it. To
enforce it mechanically instead, add a required check that fails when the PR body has no
`Closes #`, `Fixes #` or `Refs #` line.
