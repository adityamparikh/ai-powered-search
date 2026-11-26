---
name: run-rag-eval
description: Measure a change to the ai-powered-search RAG pipeline (/ask) with the ragEval harness and compare it with the committed baseline. Billed - always get the maintainer's go-ahead first. Use when asked to "run the eval", "measure this RAG change", "compare against the baseline", or before flipping a search.rag.* flag.
---

# Run the RAG evaluation

`./gradlew ragEval` sends ~50 cases through `/ask` with real Claude and OpenAI calls, plus an
Ollama judge. **It costs money.** Before running it:

1. Ask the maintainer, stating the label, the properties, whether the judge runs, and roughly how
   many cases (`-Drag.eval.cases` narrows it). Wait for a yes in this session.
2. Check the keys are exported (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`; `TYPESAFE_API_KEY` for
   decisions stages). Without them every case skips and the run proves nothing.
3. Check the code under test is green first: `./gradlew verify`.

## Run

```bash
# Baseline (all stage flags at their defaults)
./gradlew ragEval -PallowBilledCalls=true

# Candidate: ';'-separated Spring properties, and a label for the report copy
./gradlew ragEval -PallowBilledCalls=true \
  -Drag.eval.props='search.rag.decisions.enabled=true' -Drag.eval.label=decisions

# Cheaper: retrieval metrics only, or a subset of cases/categories
./gradlew ragEval -PallowBilledCalls=true -Drag.eval.judge=false
./gradlew ragEval -PallowBilledCalls=true -Drag.eval.cases=follow-up,fu-01
# Reuse a local Ollama for the judge instead of a container
./gradlew ragEval -PallowBilledCalls=true -Drag.eval.ollama-url=http://localhost:11434
```

`-PallowBilledCalls=true` is the opt-in guard; never add it without the go-ahead above.

## Read and compare

- Report: `build/reports/rag-eval/report.md` (and `report.json`, plus a copy named after the label).
- Compare per category with `docs/rag-eval-baseline.md`. The rules for whether a difference is
  real, and the thresholds for flipping a stage flag, are in `docs/rag-evaluation.md`
  ("Thresholds"); small categories need the sample-size caution described there.
- Put the comparison table in the PR description. Update `docs/rag-eval-baseline.md` only when the
  maintainer agrees the baseline itself has moved (for example after a default changed).

## When it fails

A case that errors is a finding, not noise: read the stack trace in `build/test-results/ragEval/`.
The harness collection must be built from `solr-config/conf`; on `_default` the keyword leg
returns nothing and recall collapses.
