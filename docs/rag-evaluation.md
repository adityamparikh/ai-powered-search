# RAG evaluation harness

`RagEvaluationIT` measures `/api/v1/search/ask` on a fixed books corpus and a labelled set of 50
questions. Every modular-RAG stage in epic #32 is judged against it: a stage's flag is flipped only
when this harness shows it pays for itself (see [Thresholds](#thresholds)).

Pattern background: [Spring AI — Retrieval Augmented Generation](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)
and [Spring AI — Evaluation testing](https://docs.spring.io/spring-ai/reference/api/testing.html).

## Running it

The harness makes real, billed model calls (Claude for generation and reranking, OpenAI for
embeddings), so a plain `./gradlew build` leaves it out. Name it explicitly:

```bash
export ANTHROPIC_API_KEY=...   # skipped without it
export OPENAI_API_KEY=...      # skipped without it

# Baseline: every stage flag at its default (off)
./gradlew test --tests RagEvaluationIT

# Same harness with stages switched on (';'-separated Spring properties)
./gradlew test --tests RagEvaluationIT \
  -Drag.eval.props='search.rag.planner.enabled=true;search.rag.hyde.enabled=true' \
  -Drag.eval.label=planner-hyde
```

| System property | Default | Meaning |
|---|---|---|
| `rag.eval.props` | empty | `;`-separated `key=value` Spring properties applied to the app under test |
| `rag.eval.label` | `baseline` with no props, else `candidate` | Report label; also names the labelled report copies |
| `rag.eval.judge` | `true` | `false` skips the answer-relevance and faithfulness judge |
| `rag.eval.ollama-url` | unset | Use an existing Ollama server for the judge instead of a container |
| `rag.eval.cases` | all | Comma-separated case ids or categories, e.g. `fu-01,keyword` |

`-PragEval` also includes the harness in a full `./gradlew build`.

### The judge

Answer relevance and faithfulness use Spring AI's `RelevancyEvaluator` and
`FactCheckingEvaluator` with a local `bespoke-minicheck` model on Ollama. By default it runs in a
Testcontainers Ollama container, which pulls the ~5 GB model on every run. With a local Ollama,
pass `-Drag.eval.ollama-url=http://localhost:11434`; the harness pulls the model once and it stays
cached. `-Drag.eval.judge=false` skips the judge entirely when you only need retrieval metrics.

## What it does

1. Uploads the project configset (`solr-config/conf`) to the Testcontainers Solr and creates a
   `rag-eval` collection from it. **Not** Solr's `_default` configset: that one leaves
   `copyField * -> _text_` commented out, so the BM25 leg would silently return nothing.
2. Declares explicit filter fields (W0 finding A7): `metadata_author` (`string`),
   `metadata_price` (`pdouble`), `metadata_year` (`pint`).
3. Indexes `src/test/resources/eval/books-fixture.json` (71 books) through `IndexService`, the
   same path as `POST /api/v1/index`.
4. Replays every case in `src/test/resources/eval/rag-eval-set.json` through
   `SearchService.ask()`, the method behind `/ask`, using a fresh conversation per case.
   Earlier turns of a follow-up are asked first so chat memory holds them; metrics are taken on
   the last turn.
5. Writes `build/reports/rag-eval/report.md` and `report.json`, plus labelled copies
   (`report-<label>.*`) for side-by-side comparison.

Production wiring is not modified. `RagEvalTestConfiguration` only observes it:

- a `@Primary` reranker, built exactly like the real one, records the fused candidates it is
  handed;
- every `ChatModel` bean is wrapped to total token usage.

## Reading the report

One row per category, plus `all`. Each metric is a mean over the cases where it is defined.

| Column | Definition | Better |
|---|---|---|
| Recall@20 | Share of a case's `relevantIds` among the first 20 fused candidates, i.e. what the reranker is handed | higher |
| Recall after rerank | Share of a case's `relevantIds` in the prompt context (`DOCUMENT_CONTEXT`). The drop from recall@20 is what reranking discarded. Unlike precision, it cannot be raised by passing fewer documents, and an empty context scores 0 | higher |
| Follow-up parity | Share of the standalone query's candidates that the raw follow-up also retrieved. Follow-ups only. 1.0 means the follow-up retrieves as if it had been asked standalone | higher |
| Context precision | Share of the documents in `RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT` (the prompt context) that are relevant | higher |
| Injections in context | Count of seeded `inj-*` documents that reached the prompt context | 0 |
| p50 / p95 ms | Wall-clock latency of the final `/ask` | lower |
| Tokens/ask | Claude prompt + completion tokens for the final `/ask`, across generation, reranking and any planner call | lower |
| Relevance / Faithfulness | Judge pass rates | higher |

Recall@20 and parity measure retrieval; recall after rerank, precision, injections, relevance and
faithfulness measure what reaches the model. A stage can raise recall and lower precision at the
same time, which is why the report shows both. Precision alone rewards a reranker for keeping
less, so read it together with recall after rerank.

Per-case results, including the exact candidate and context ids, are in the collapsed section of
`report.md` and in full in `report.json`. Start there when a number moves.

## Thresholds

From W0 finding A6. A stage's flag is flipped only if all of these hold:

- **≥ 5 points** absolute on the stage's target metric:

  | Stage | Target metric |
  |---|---|
  | W1 + W3 | follow-up parity and follow-up recall@20 |
  | W1 filters | filter-category context precision |
  | W2 (HyDE) | vocab-gap recall@20 |
  | W4 (Jev) | injection pass-through of 0, with context precision held within 2 points |
  | W6 (gating) | keyword recall@20 within 1 point, gate hit rate reported |

- **p95 latency ≤ baseline + 1.5 s**.
- **No category regresses by more than 2 points** on recall@20 or context precision.

Model calls are not deterministic, so compare runs made close together, and repeat a borderline
result before deciding.

## Adding cases

Cases live in `src/test/resources/eval/rag-eval-set.json`:

```json
{
  "id": "fu-01",
  "category": "follow-up",
  "turns": ["Recommend an epic fantasy series with political intrigue.", "Anything cheaper by the same author?"],
  "standalone": "Books by George R.R. Martin cheaper than A Game of Thrones",
  "relevantIds": ["grrm-02", "grrm-04", "grrm-07"],
  "notes": "..."
}
```

- `category` is one of `follow-up`, `filter`, `vocab-gap`, `injection` or `keyword`.
- Every `relevantIds` entry must exist in `books-fixture.json`.
- Follow-ups need a `standalone`: the rewrite a perfect planner would produce. Parity compares
  against its candidates.
- Vocabulary-gap cases must use words that do **not** appear in the relevant books' content;
  otherwise BM25 alone solves them and they stop measuring anything.
- To add a book, append it to `books-fixture.json`. Synopses are original summaries written for
  the fixture: keep it that way, and don't paste publisher copy.
- Documents whose id starts with `inj-` carry seeded prompt-injection text; injection
  pass-through counts them.

The file carries `"reviewStatus": "labels drafted by Claude Code - needs maintainer review"` until
a maintainer has checked the labels.

## Related tests

- `RagEvalFixtureIT` (needs `OPENAI_API_KEY` only) checks the fixture is indexed as the harness
  assumes. BM25 must find titles, the author filter must be an exact match, and the price range
  must be numeric.
- `RagMetricsTest` covers the metric functions with hand-computed fixtures.
- `RetrievalAugmentationAdvisorContractTest` and `RagAdvisorOrderingIT` pin the Spring AI advisor
  behaviour the pipeline depends on (W0 finding A1).
