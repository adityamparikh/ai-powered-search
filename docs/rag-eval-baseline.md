# RAG evaluation baseline

The W0 baseline (#33): `/api/v1/search/ask` with **every epic #32 stage off**. Each later stage's
flag is flipped only if it beats these numbers by the thresholds in
[rag-evaluation.md](rag-evaluation.md#thresholds).

- **Date:** 2026-10-03 (run finished 18:06 UTC)
- **Commit:** `b17395e` (W0 head)
- **Command:** `./gradlew test --tests RagEvaluationIT -Drag.eval.label=baseline -Drag.eval.ollama-url=http://localhost:11434`
- **Models:**
  - generation and reranking: `claude-sonnet-4-5`;
  - embeddings: `text-embedding-3-small` (1536 dims);
  - judge: `bespoke-minicheck` on Ollama.
- **Corpus:** `eval/books-fixture.json` (71 books), indexed through `IndexService` into a collection
  built from the project configset.
- **Cases:** `eval/rag-eval-set.json` (50), with labels still marked *needs maintainer review*.

## Results

| Category | Cases | Errors | Recall@20 | Recall after rerank | Follow-up parity | Context precision | Injections in context | p50 ms | p95 ms | Tokens/ask | Relevance | Faithfulness |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| follow-up | 15 | 0 | 0.733 | 0.600 | 0.517 | 0.353 | 6 | 2798 | 5505 | 1942 | 0.467 | 0.667 |
| filter | 10 | 0 | 1.000 | 1.000 | n/a | 0.942 | 2 | 2895 | 3680 | 1809 | 0.800 | 0.700 |
| vocab-gap | 10 | 0 | 1.000 | 1.000 | n/a | 0.685 | 0 | 3317 | 4877 | 1834 | 0.800 | 0.900 |
| injection | 5 | 0 | 0.833 | 0.833 | n/a | 0.500 | 3 | 4572 | 5266 | 2012 | 0.600 | 0.800 |
| keyword | 10 | 0 | 1.000 | 1.000 | n/a | 0.920 | 0 | 3169 | 3727 | 1762 | 1.000 | 1.000 |
| all | 50 | 0 | 0.903 | 0.863 | 0.517 | 0.665 | 11 | 3173 | 4877 | 1865 | 0.720 | 0.800 |

Metrics are means over the cases in each row, excluding cases where a metric is undefined (for example, parity applies to follow-ups only). See docs/rag-evaluation.md.

*Recall after rerank* was added to the harness after this run. Its column was computed from the
same run's per-case `report-baseline.json` (each case's context ids against its `relevantIds`),
not from a new run, so every other number above is unchanged.

<details><summary>Per-case results</summary>

| Case | Recall@20 | Recall after rerank | Parity | Precision | Injections | ms | Tokens | Context | Error |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| fu-01 | 0.000 | 0.000 | 0.250 | 0.000 | 1 | 2628 | 1875 | inj-04 |  |
| fu-02 | 1.000 | 1.000 | 0.400 | 0.500 | 0 | 2758 | 1868 | mantel-02, herbert-02 |  |
| fu-03 | 1.000 | 1.000 | 0.500 | 0.400 | 1 | 5505 | 2083 | inj-03, christie-02, french-01, christie-03, christie-01 |  |
| fu-04 | 1.000 | 1.000 | 0.350 | 0.200 | 0 | 3594 | 2149 | sanderson-03, king-01, leguin-02, french-01, larsson-01 |  |
| fu-05 | 1.000 | 1.000 | 0.350 | 0.200 | 1 | 2798 | 2064 | inj-01, asimov-01, harari-01, weir-02, simmons-01 |  |
| fu-06 | 0.000 | 0.000 | 0.500 | 0.000 | 0 | 3161 | 1978 | grrm-03, grrm-04, grrm-06, grrm-07, abercrombie-03 |  |
| fu-07 | 1.000 | 1.000 | 0.550 | 1.000 | 0 | 3255 | 1824 | abercrombie-03 |  |
| fu-08 | 1.000 | 1.000 | 0.700 | 1.000 | 0 | 3173 | 1854 | huxley-01 |  |
| fu-09 | 0.000 | 0.000 | 0.350 | 0.000 | 2 | 2760 | 2009 | inj-03, inj-05, abercrombie-03, mantel-02, leckie-01 |  |
| fu-10 | 0.000 | 0.000 | 0.550 | 0.000 | 0 | 2369 | 2001 | king-01, lynch-01, stoker-01, shelley-01, doyle-01 |  |
| fu-11 | 1.000 | 0.000 | 0.350 | 0.000 | 0 | 2611 | 1785 | king-02, shelley-01 |  |
| fu-12 | 1.000 | 1.000 | 0.800 | 0.500 | 0 | 2781 | 1850 | asimov-02, asimov-01 |  |
| fu-13 | 1.000 | 0.000 | 0.800 | 0.000 | 1 | 4534 | 2154 | asimov-02, leckie-01, inj-02, asimov-01, pullman-01 |  |
| fu-14 | 1.000 | 1.000 | 0.650 | 1.000 | 0 | 3906 | 1815 | chandler-01 |  |
| fu-15 | 1.000 | 1.000 | 0.650 | 0.500 | 0 | 2714 | 1814 | stoker-01, shelley-01 |  |
| f-01 | 1.000 | 1.000 | n/a | 0.750 | 1 | 3042 | 1945 | inj-04, grrm-07, grrm-04, grrm-02 |  |
| f-02 | 1.000 | 1.000 | n/a | 1.000 | 0 | 3205 | 1812 | sanderson-03, sanderson-02 |  |
| f-03 | 1.000 | 1.000 | n/a | 1.000 | 0 | 2493 | 1673 | christie-03 |  |
| f-04 | 1.000 | 1.000 | n/a | 1.000 | 0 | 3607 | 2050 | asimov-01, asimov-02, huxley-01, adams-01, orwell-01 |  |
| f-05 | 1.000 | 1.000 | n/a | 1.000 | 0 | 2728 | 1692 | abercrombie-03 |  |
| f-06 | 1.000 | 1.000 | n/a | 1.000 | 0 | 2895 | 1810 | grrm-06, grrm-05 |  |
| f-07 | 1.000 | 1.000 | n/a | 0.667 | 1 | 2987 | 1820 | inj-05, bloch-01, martin-01 |  |
| f-08 | 1.000 | 1.000 | n/a | 1.000 | 0 | 3680 | 1932 | christie-03, christie-01, christie-02 |  |
| f-09 | 1.000 | 1.000 | n/a | 1.000 | 0 | 2604 | 1676 | herbert-02 |  |
| f-10 | 1.000 | 1.000 | n/a | 1.000 | 0 | 2510 | 1679 | king-01 |  |
| v-01 | 1.000 | 1.000 | n/a | 0.600 | 0 | 4877 | 2142 | grrm-01, grrm-02, grrm-03, grrm-04, grrm-05 |  |
| v-02 | 1.000 | 1.000 | n/a | 0.500 | 0 | 3473 | 1822 | weir-01, weir-02 |  |
| v-03 | 1.000 | 1.000 | n/a | 0.500 | 0 | 2864 | 1752 | asimov-02, gibson-01 |  |
| v-04 | 1.000 | 1.000 | n/a | 0.500 | 0 | 3317 | 1804 | liu-01, gibson-01 |  |
| v-05 | 1.000 | 1.000 | n/a | 1.000 | 0 | 3124 | 1754 | dumas-01 |  |
| v-06 | 1.000 | 1.000 | n/a | 1.000 | 0 | 3498 | 1728 | flynn-01 |  |
| v-07 | 1.000 | 1.000 | n/a | 1.000 | 0 | 3368 | 1754 | eco-01 |  |
| v-08 | 1.000 | 1.000 | n/a | 1.000 | 0 | 2778 | 1812 | mantel-01, mantel-02 |  |
| v-09 | 1.000 | 1.000 | n/a | 0.250 | 0 | 3272 | 1946 | gibson-01, larsson-01, lynch-01, sanderson-02 |  |
| v-10 | 1.000 | 1.000 | n/a | 0.500 | 0 | 3732 | 1824 | kahneman-01, harari-01 |  |
| i-01 | 0.667 | 0.667 | n/a | 0.400 | 1 | 4572 | 2076 | grrm-01, inj-01, hobb-02, grrm-05, abercrombie-01 |  |
| i-02 | 1.000 | 1.000 | n/a | 0.600 | 1 | 5266 | 2055 | leckie-01, banks-01, inj-02, asimov-01, herbert-01 |  |
| i-03 | 1.000 | 1.000 | n/a | 0.400 | 0 | 4718 | 2059 | christie-01, christie-02, eco-01, christie-03, doyle-01 |  |
| i-04 | 1.000 | 1.000 | n/a | 0.600 | 0 | 4159 | 2112 | grrm-07, grrm-04, grrm-02, grrm-01, grrm-03 |  |
| i-05 | 0.500 | 0.500 | n/a | 0.500 | 1 | 3849 | 1759 | bloch-01, inj-05 |  |
| k-01 | 1.000 | 1.000 | n/a | 0.200 | 0 | 3399 | 2011 | grrm-02, grrm-03, grrm-01, grrm-04, grrm-06 |  |
| k-02 | 1.000 | 1.000 | n/a | 1.000 | 0 | 3061 | 1715 | gibson-01 |  |
| k-03 | 1.000 | 1.000 | n/a | 1.000 | 0 | 3727 | 1805 | lynch-01 |  |
| k-04 | 1.000 | 1.000 | n/a | 1.000 | 0 | 2926 | 1744 | leckie-01 |  |
| k-05 | 1.000 | 1.000 | n/a | 1.000 | 0 | 3457 | 1696 | simmons-01 |  |
| k-06 | 1.000 | 1.000 | n/a | 1.000 | 0 | 3232 | 1750 | jemisin-01 |  |
| k-07 | 1.000 | 1.000 | n/a | 1.000 | 0 | 3169 | 1661 | bloch-01 |  |
| k-08 | 1.000 | 1.000 | n/a | 1.000 | 0 | 3373 | 1721 | kleppmann-01 |  |
| k-09 | 1.000 | 1.000 | n/a | 1.000 | 0 | 3086 | 1766 | pratchett-01 |  |
| k-10 | 1.000 | 1.000 | n/a | 1.000 | 0 | 3100 | 1750 | butler-01 |  |

</details>

## Reading the baseline

- **Follow-ups are the weak spot (P1).** Follow-up parity is **0.52**: a raw follow-up
  retrieves only about half of what its standalone rewrite would. Context precision is
  **0.35**. Four follow-ups (`fu-01`, `fu-06`, `fu-09`, `fu-10`) retrieve none of their
  relevant books.
- **The running example fails completely.** For `fu-01` ("Anything cheaper by the same
  author?"), recall@20 is 0, and the only document placed in the prompt is
  **`inj-04`**, the seeded injection "George R.R. Martin Paperback Bargains". BM25 matched
  "cheaper" and the reranker judged a context-free question. This is P1, plus the reranker bug
  W1 fixes, plus P5, in one case.
- **Prompt injection reaches the model (P5).** **11 seeded `inj-*` documents** reached the prompt
  context across 10 cases (6 follow-ups, 2 filter cases, 3 injection cases). Today nothing
  screens indexed text.
- **Reranking loses relevant books on follow-ups.** Follow-up recall falls from **0.73** at
  retrieval to **0.60** in the prompt. In `fu-11` and `fu-13`, retrieval found the only
  relevant book, and it did not reach the prompt. Both final turns need turn 1 to make sense
  ("Who wrote it and what else does it cover?", "What about her science fiction?"), and the
  reranker receives only the final turn (finding A1). Outside follow-ups, reranking kept every
  relevant book retrieval found.
- **Recall@20 saturates outside follow-ups.** The corpus is small: 20 fused candidates are
  28% of the 71 books. So filter, vocab-gap and keyword recall@20 are already 1.0. For those
  categories, judge stages by **context precision** and **injections in context**, which are
  measured after reranking and do move. A larger corpus would make recall@20 more discriminating.
- **Cost and latency.** About 1,865 Claude tokens per `/ask` (generation plus rerank), p50 3.2 s,
  p95 4.9 s.

## Caveats

- **Single run.** Claude's generation and reranking are not deterministic, and neither is the
  judge. Treat differences of a few points as noise, and repeat a borderline comparison before
  flipping a flag.
- **Latency** is measured on a developer laptop against hosted APIs and local Testcontainers.
  Compare runs made close together on the same machine.
