---
name: add-rag-stage
description: Add a new stage to the ai-powered-search /ask pipeline (a query transformer, retriever change, document post-processor, reranker or decisions provider) the way the project requires - behind an off-by-default property, fail-safe, judged against the standalone rewrite, tested and measured. Use when asked to add HyDE, a planner, a filter, a new reranker, a new decisions provider or any other RAG step.
---

# Add a RAG stage

The chain is built in `AiConfig.ragChatClient`: `MessageChatMemoryAdvisor` (runs first by
`getOrder()`), then `RetrievalAugmentationAdvisor` with `queryTransformer` (follow-up rewrite) ->
`HybridDocumentRetriever` -> pass-through joiner -> post-processors (decisions filter, then
reranker) -> `ContextualQueryAugmenter(allowEmptyContext=true)`. The diagram is in
`docs/architecture.md`; properties are in `docs/rag-pipeline.md`.

## Rules

- **Off by default.** A `@Bean` with `@ConditionalOnProperty(name = "search.rag.<stage>.enabled",
  havingValue = "true")` (no `matchIfMissing`), or a default that reproduces today's behaviour.
  With the flag off, `/ask` returns the same documents in the same order; add a regression test.
- **Fail safe.** Catch the stage's failures (timeout, malformed model output, remote outage) and
  return the input unchanged with a `log.warn(...)`. Never let an optional stage produce a 5xx.
- **Judge the rewrite.** A `DocumentPostProcessor` added to the list must be wrapped with
  `AiConfig.judgedAgainstTheRewrite(...)`; the advisor passes the original follow-up otherwise.
- **Keep the order.** Never replace the pass-through joiner; never sort candidates by `score`
  (hybrid rows have none). Screening runs before reranking.
- **Decisions providers** are a `@Configuration` contributing beans named
  `AiConfig.DECISIONS_FILTER` / `DECISIONS_RERANKER`, selected by `search.rag.decisions.provider`;
  `AiConfig` must not learn the provider's name. Follow `TypeSafeDecisionsConfig`.
- A missing optional collaborator (no key, a typo in a provider name) logs a WARN at startup, like
  the existing decisions wiring, rather than failing silently.

## Tests

- Unit tests for the stage: happy path, each fallback, empty input, and that a failure returns the
  input with a WARN.
- A Spring context test that the bean is absent with the flag off and present with it on (see
  `TypeSafeDecisionsConfigTest`, `PostProcessorSelectionTest`).
- An IT with Testcontainers and a mocked `ChatModel` (see `TypeSafeDecisionsScreeningIT`,
  `RagAdvisorOrderingIT`) showing the stage's effect on what reaches the prompt.
- Anything that needs a real model: `@EnabledIfEnvironmentVariable(...)` plus `@LiveModelTest`.
- Add eval cases to `src/test/resources/eval/rag-eval-set.json` if the stage targets a category
  the set does not cover (`docs/rag-evaluation.md` explains the format; `RagEvalHarnessTest`
  validates it).

## Finish

`./gradlew spotlessApply && ./gradlew verify`; document the property, its default, what it costs
(extra model calls per `/ask`) and its failure behaviour in `docs/rag-pipeline.md`; redraw the
`/ask` sequence in `docs/architecture.md`. Flipping the default is a separate decision for the
maintainer, after a `ragEval` comparison (`run-rag-eval` skill) meets the thresholds in
`docs/rag-evaluation.md`.
