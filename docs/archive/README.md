# Archived documents

Historical write-ups, kept for the reasoning they record. **They are not maintained and are not
current.** Most predate the move to Solr's combined query: they describe an application-side
`RrfMerger` (removed), RAG through `QuestionAnswerAdvisor` (replaced by
`RetrievalAugmentationAdvisor` with `HybridDocumentRetriever`), or config files that do not exist.

When one of these disagrees with the code, the code wins, then [CLAUDE.md](../../CLAUDE.md) and the
current docs in [docs/](..). Do not update these files; fix or extend the current docs instead.

| File | What it was | Current replacement |
|---|---|---|
| `TECHNICAL_DETAILS.md` | Deep dive into every search path | [../architecture.md](../architecture.md), [../api.md](../api.md) |
| `VECTOR_SEARCH_GUIDE.md` | Vector store and indexing guide | [../hybrid-search.md](../hybrid-search.md) |
| `VECTOR_SEARCH_ANALYSIS.md` | Analysis of the vector store implementation | [../hybrid-search.md](../hybrid-search.md) |
| `HYBRID_SEARCH_COMPARISON.md` | Client-side RRF compared with Craig Walls' Lucene recipe | [../hybrid-search.md](../hybrid-search.md), [../../SOLR_ENHANCEMENTS.md](../../SOLR_ENHANCEMENTS.md) |
| `CLAUDE_PR_REVIEW.md` | Claude PR review setup; refers to a `.github/claude-review-config.yml` that never existed | The prompt in `.github/workflows/claude-code-review.yml` |
