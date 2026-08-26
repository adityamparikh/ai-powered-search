# Hybrid Search: this repo (Solr) vs. Craig Walls' Spring AI recipe (Lucene)

Comparison of `ai-powered-search`'s hybrid search against
*"Spring AI Recipe: Better RAG Results with Hybrid Search"* by Craig Walls
(author of *Spring AI in Action* / *Spring in Action*), Aug 2026.
Reference code: https://github.com/habuma/spring-ai-recipes

---

> **Status update.** Recommendations 1 and 2 below have since been implemented on this branch:
> RAG now retrieves through `HybridDocumentRetriever` via `RetrievalAugmentationAdvisor`
> (§4.1), `AskResponse.sources` is populated from `DOCUMENT_CONTEXT`, and the `minScore`
> defect (§5.2) is fixed — it now filters the vector leg pre-fusion and never thresholds the
> fused RRF score. Items 3–5 remain open. The analysis below describes the state *before*
> those changes. The sequential-execution defect (§5.1) is also fixed: the two legs now run
> concurrently on virtual threads, so the javadoc's "concurrently" claim is finally true.

## TL;DR

The **RRF math is the same**. The **architecture is not**.

His hybrid search is a *retrieval component inside the RAG pipeline* — it feeds the LLM.
Ours is a *search API endpoint* — it feeds an HTTP response. Our RAG endpoint (`/api/v1/search/ask`)
retrieves with `QuestionAnswerAdvisor(vectorStore)`, which is **vector-only**.

> We built the fusion engine and never wired it into RAG. The exact benefit the
> article's title promises — *better RAG results* — is the gap in this repo.

Beyond that: our retrieval substrate is substantially stronger (one index, filters,
facets, distributed), his pipeline integration is substantially stronger (modular RAG SPI,
query transformers). And our hybrid path has two concrete defects worth fixing regardless.

---

## 1. Side-by-side

| Dimension | This repo | Article |
|---|---|---|
| Lexical engine | Solr 9.10 `edismax` over `_text_` | Embedded Lucene 10.5.1, `StandardAnalyzer`, `QueryParser` |
| Vector engine | Solr `DenseVectorField` KNN (HNSW, cosine, 1536d) | Spring AI `VectorStore.similaritySearch()` |
| Indexes | **One** (text + vector in same Solr doc) | **Two** (VectorStore + `FSDirectory` at `/tmp/lucene/boot-docs`) |
| Fusion | `RrfMerger` — standalone class, k configurable, unit-tested | `reciprocalRankFusion()` — private method, k hardcoded |
| RRF formula | `1/(k+rank)`, k=60, 1-indexed, summed by id | identical |
| Candidates per leg | `topK * 2` (`OVER_FETCH_MULTIPLIER`) | exactly 10, returns 10 (no over-fetch) |
| Tie-break | `LinkedHashMap` → deterministic | `HashMap` → arbitrary |
| Score provenance | `rrf_score`, `keyword_score`, `vector_score`, `keyword_rank`, `vector_rank` | none (debug logs only) |
| Filtering | Claude-generated Solr `fq` applied to **both** legs pre-fusion | none |
| Query understanding | Bespoke Claude call → `q`/`fq`/`sort`/`fl`/facets JSON | Spring AI `QueryTransformer[]` (HyDE, rewrite, …) |
| Consumed by | `GET /{collection}/hybrid` REST endpoint | `RetrievalAugmentationAdvisor` → the LLM |
| RAG retrieval | `QuestionAnswerAdvisor(vectorStore)` — **vector only** | `HybridDocumentRetriever` — hybrid |
| Fallback | hybrid → keyword-only → vector-only cascade | none |
| Extras | highlighting, faceting, spellcheck, schema introspection | none |
| Ops footprint | Solr + ZooKeeper + Docker | a directory on disk |

---

## 2. The RRF implementations are near-identical

His (`HybridDocumentRetriever.addRrfScores`):

```java
final int k = 60;
for (int i = 0; i < results.size(); i++) {
    var document = results.get(i);
    var rank = i + 1;
    documents.putIfAbsent(document.getId(), document);
    scores.merge(document.getId(), 1.0 / (k + rank), Double::sum);
}
```

Ours (`RrfMerger.merge`, RrfMerger.java:120-152) — same formula, same constant, same
1-indexing, same sum-by-id. Differences are all in packaging, and ours is better packaged:

- **Standalone + injectable k** — `new RrfMerger(30)` works; his `k` is a `final int` local.
  (Though `SearchRepository:247` does `new RrfMerger()` per call, so we never use that knob.)
- **Deterministic ordering** — we use `LinkedHashMap` with an explicit comment. He uses
  `HashMap` and sorts by value, so documents with equal RRF scores come back in hash order.
  Ties are common (any doc appearing at the same rank in only one list), so his top-10
  can reorder between JVM runs.
- **Provenance retained** — RRF discards raw scores by construction. We re-attach
  `keyword_rank` / `vector_rank` / `keyword_score` / `vector_score` to every result, which
  is what makes "why did this rank 3rd?" answerable. He logs three lists at DEBUG and drops it.
- **Field merging** — we merge fields across both legs (vector wins conflicts, `id` protected).
  He uses `putIfAbsent`, so whichever list saw the doc first supplies the payload.
- **Tested** — `RrfMergerTest` exists.

---

## 3. Where this repo is genuinely stronger

### 3.1 One index instead of two

His ingestion dual-writes:

```java
vectorStore.add(chunks);
luceneDocumentWriter.add(chunks);
```

Two stores, no transaction, nothing reconciling them. The article even warns you to
*"delete the existing vector store data if you previously worked through the HyDE recipe"* —
that's the drift problem surfacing in the setup instructions. A delete that hits the vector
store leaves an orphaned Lucene doc that BM25 will still surface into the LLM's context.

Ours keeps `content` and the `knn_vector_1536` field on the same Solr document. One write,
one delete, no divergence possible.

### 3.2 His Lucene reader is frozen at startup

```java
public LuceneSearch(Path indexPath) throws IOException {
    this.reader = DirectoryReader.open(directory);
    this.searcher = new IndexSearcher(reader);
}
```

`DirectoryReader` is opened once, at bean creation, and never refreshed — no
`DirectoryReader.openIfChanged()`, no `SearcherManager`. Any document indexed after
application startup is invisible to BM25 forever. That's fine for his demo (docs are
ingested by a separate app before boot), but it is not a live-index design.

Solr gives us NRT visibility on commit, plus replication and sharding.

### 3.3 Metadata is searchable here, opaque there

```java
luceneDocument.add(new StoredField(METADATA_FIELD,
    objectMapper.writeValueAsString(document.getMetadata())));
```

`StoredField` is stored but **not indexed** — his metadata is retrievable and nothing more.
You cannot filter or facet on it. Ours uses `metadata_*` dynamic fields, so metadata is
indexed, filterable, and facetable.

### 3.4 Filters, and they apply to both legs

`setupFilterQuery()` puts the Claude-generated `fq` on both the edismax and the KNN request
(SearchRepository.java:295, 326), so fusion operates over an already-constrained candidate set —
the correct order of operations. The article has no filtering at all.

### 3.5 Over-fetch

We pull `topK * 2` per leg before fusing. He pulls exactly 10 per leg and returns 10.
Without over-fetch, a document sitting at rank 11 in *both* lists — a strong hybrid
candidate by definition — can never surface. This measurably caps the benefit his own
article is demonstrating. (Ours at 2x is defensible; 3–5x is the more common tuning.)

### 3.6 Richer analysis chain

Solr's schema gives us synonym expansion, stemming, and field boosting configured per
field type. He gets bare `StandardAnalyzer`.

---

## 4. Where the article is stronger

### 4.1 Hybrid retrieval actually reaches the LLM — the headline difference

```java
var ragAdvisor = RetrievalAugmentationAdvisor.builder()
    .queryTransformers(queryTransformers)
    .documentRetriever(hybridDocumentRetriever)   // <-- hybrid feeds the prompt
    .documentJoiner(/* pass-through */)
    .build();
```

Ours (`AiConfig.java:213`):

```java
QuestionAnswerAdvisor.builder(vectorStore)      // <-- vector only
    .searchRequest(SearchRequest.builder().topK(5).similarityThreshold(0.3).build())
    .build()
```

`grep` confirms this repo has **zero** references to `DocumentRetriever`,
`RetrievalAugmentationAdvisor`, `DocumentJoiner`, or `QueryTransformer`.
`SearchService.hybridSearch()` is reachable only from `SearchController:208`.
`SearchService.ask()` never touches it.

So on the specific question the article asks — *does hybrid search improve your RAG answers?* —
this repo's answer is currently "we can't tell, because RAG doesn't use it."

### 4.2 Modular RAG SPI vs. bespoke plumbing

By implementing `DocumentRetriever` he gets, for free and composably:
`QueryTransformer[]` (HyDE from his prior recipe, rewrite, translation, multi-query expansion),
`DocumentJoiner`, query augmentation, and swappability. Our query understanding is a
hand-rolled Claude call returning Solr JSON — more powerful for *search* (ranges, sorts, facets),
but it is not a retrieval pipeline and composes with nothing.

### 4.3 He escapes the user query; we don't

```java
var query = parser.parse(QueryParser.escape(queryText));
```

We pass the LLM-generated `q` straight into edismax (SearchRepository.java:290). edismax is
lenient and won't throw, but it still *interprets* `AND`/`OR`/`+`/`-`/`"`/`~`, so a query
containing those characters is parsed as syntax rather than matched literally.

### 4.4 He documents the `DocumentJoiner` landmine

> *"The default, `ConcatenationDocumentJoiner`, re-sorts its results based on each document's
> score, effectively undoing the ordering established by RRF."*

Hence the no-op joiner. This is precisely the trap we would fall into the moment we wire
`RrfMerger` into a `RetrievalAugmentationAdvisor` — worth knowing before we try.

He also notes the framework constraint driving the design: `RetrievalAugmentationAdvisor`
accepts only **one** `DocumentRetriever`, which is why fusion lives inside the retriever
rather than in the joiner where it conceptually belongs.

### 4.5 Operational simplicity

Two Gradle lines and a filesystem path. No ZooKeeper, no Docker, no schema management,
no collection bootstrap script.

---

## 5. Defects found in our hybrid path

These are independent of the comparison and worth fixing on their own.

### 5.1 The "concurrent" claim is false — hybrid runs sequentially

`SearchRepository.java:206` — *"runs keyword and vector searches **concurrently**"*
`SearchRepository.java:211-212` — *"Launch keyword search and vector search in parallel"*

The code (SearchRepository.java:238-240):

```java
// Virtual threads handle blocking I/O efficiently — no need for CompletableFuture
List<Map<String, Object>> keywordResults = executeKeywordSearch(...);
List<Map<String, Object>> vectorResults  = executeVectorSearch(...);
```

Two sequential blocking statements. Virtual threads make blocking *cheap*; they do not make
sequential statements *concurrent*. Current hybrid latency is
`embed + keywordRTT + vectorRTT`, serialized. Two independent I/O calls on the critical path
is exactly the case `StructuredTaskScope` (or plain `CompletableFuture`) exists for.
The article is also sequential — but it doesn't claim otherwise.

### 5.2 `minScore` is documented as `[0..1]` but compared against an RRF score

Documented as *"minimum similarity score threshold [0..1]"* in three places
(SearchController.java:173, SearchController.java:204, SearchService.java:196).

After fusion, `passesMinScore` (SearchRepository.java:253) compares it against `rrf_score`,
whose theoretical maximum is `2/61 ≈ 0.0328`.

Consequence: **any `minScore` ≥ 0.033 empties the result set.** That triggers
`fallbackSearch`, which re-runs keyword search and filters by raw **BM25** score ≥ `minScore` —
a different, unbounded scale. So the documented example value `minScore=0.5` silently
degrades hybrid search into a keyword-only search, and reports success.

Fix options: normalize the RRF score to `[0..1]`, apply `minScore` to each leg *before*
fusion, or rename/redocument the parameter as an RRF-score floor.

### 5.3 Per-query schema introspection tax

Every hybrid request calls `generateQueryWithAI()` → `buildQueryUserMessage()` →
`getFieldsWithSchema()` (SearchService.java:300), which issues two Solr schema requests plus a
100-row sample query — then a full Claude round-trip — before any searching starts.
Nothing is cached. The article's path has neither cost.

---

## 6. What to take from this

Highest value, in order:

1. **Wire hybrid into RAG.** Implement `DocumentRetriever` over
   `SearchRepository.executeHybridRerankSearch()`, swap `QuestionAnswerAdvisor` for
   `RetrievalAugmentationAdvisor`, and supply a pass-through `DocumentJoiner` so RRF
   ordering survives (§4.4). This is the article's entire thesis and our largest gap.
2. **Fix `minScore`** (§5.2) — it silently changes which search ran.
3. **Actually parallelize the two legs**, or correct the javadoc (§5.1).
4. **Adopt `QueryTransformer`s** once the modular pipeline is in place — HyDE and
   multi-query expansion compose with hybrid retrieval and need no Solr changes.
5. Consider raising `OVER_FETCH_MULTIPLIER` to 3–5 and caching `getFieldsWithSchema`.

Nothing in the article suggests moving off Solr. Solr *is* Lucene, with BM25, the schema,
NRT, and distribution already solved — his `LuceneDocumentWriter` / `LuceneSearch` pair is
a from-scratch reimplementation of a slice of what we already run. What is worth importing
is the **shape** of his integration, not his storage.
