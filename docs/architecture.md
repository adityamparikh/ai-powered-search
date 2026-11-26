# Architecture and request flow

How a request moves through the application, endpoint by endpoint. The diagrams are
[Mermaid](https://mermaid.js.org/) and render on GitHub.

Query generation for keyword and hybrid search is stateless (no chat memory); only `/ask` keeps
conversation history. On `/ask`, a follow-up is rewritten into a standalone query before
retrieval (#50), and an optional decisions provider can screen and rerank the candidates (#51).

## Components

```mermaid
flowchart TB
    client([Client])

    subgraph app["Spring Boot application"]
        direction TB
        IC[IndexController]
        SC[SearchController]
        IS[IndexService]
        SS[SearchService]
        SR[SearchRepository]
        VS["SolrVectorStore<br/>(Spring AI VectorStore)"]
        RAG["ragChatClient<br/>RetrievalAugmentationAdvisor"]
        HDR[HybridDocumentRetriever]
        QT["queryTransformer<br/>(CompressionQueryTransformer)"]
        DF["decisionsFilter<br/>(optional, off by default)"]
        RR["Reranker<br/>Claude, or decisionsReranker"]
        EMB[EmbeddingService]
    end

    subgraph solr["Apache Solr 10.1+ / 9.11+ (SolrCloud)"]
        direction TB
        SEL["/select<br/>keyword, kNN"]
        HYB["/hybrid<br/>CombinedQuerySearchHandler<br/>(server-side RRF)"]
        UPD["/update"]
    end

    openai[["OpenAI<br/>text-embedding-3-small"]]
    claude[["Anthropic Claude"]]
    typesafe[["Decisions provider<br/>(TypeSafe Jev or a local model)"]]
    pg[("PostgreSQL<br/>chat memory")]

    client -->|"POST /api/v1/index/{c}[/batch]"| IC --> IS --> VS
    client -->|"GET /api/v1/search/{c}, /semantic, /hybrid<br/>POST /api/v1/search/ask"| SC --> SS

    SS -->|keyword| SR
    SS -->|semantic| VS
    SS -->|hybrid| SR
    SS -->|ask| RAG
    SS -->|"query generation<br/>(keyword, hybrid)"| claude

    RAG --> QT -->|"follow-ups only"| claude
    RAG --> HDR --> SR
    RAG --> DF --> typesafe
    RAG --> RR --> claude
    RR -.->|"rerank.provider=decisions"| typesafe
    RAG -->|answer| claude
    RAG <-->|"history (per conversationId)"| pg

    SR -->|keyword search| SEL
    SR -->|"hybrid: one combined query"| HYB
    SR -.->|fallback| SEL
    SR --> EMB --> openai
    VS --> openai
    VS -->|kNN| SEL
    VS -->|add| UPD
```

| Endpoint | Path through the code | Solr |
|---|---|---|
| `GET /search/{c}` (keyword) | Claude generates `q`, `fq`, sort, facets → `SearchRepository.search()` | `/select`, edismax over `_text_` |
| `GET /search/{c}/semantic` | `SolrVectorStore.similaritySearch()` (embeds the query) | `/select`, `{!knn}` |
| `GET /search/{c}/hybrid` | Claude generates `q` and `fq` → `SearchRepository.executeHybridRerankSearch()` | `/hybrid`, keyword + kNN fused by RRF |
| `POST /search/ask` | `ragChatClient`: memory → query rewrite → hybrid retrieval → optional screening → rerank → Claude | `/hybrid` (via `HybridDocumentRetriever`) |
| `POST /index/{c}[/batch]` | `SolrVectorStore.add()` (embeds each document) | `/update` |

## Hybrid search

One request to the collection's `/hybrid` handler. Solr runs both legs against the same
filters and fuses their rankings with Reciprocal Rank Fusion
(`score = Σ 1 / (60 + rank)`). The application does no merging.

```mermaid
sequenceDiagram
    autonumber
    participant SR as SearchRepository
    participant E as EmbeddingService
    participant OAI as OpenAI
    participant H as Solr /hybrid
    participant S as Solr /select

    SR->>E: embedAndFormatForSolr(query)
    E->>OAI: embed
    OAI-->>E: 1536-dim vector
    E-->>SR: "[0.12, ...]"

    SR->>H: POST combined query<br/>keyword: edismax(query, qf=_text_)<br/>vector: knn(topK=2k) or vectorSimilarity(minReturn=minScore)<br/>filter: fq · limit: k · shards.rows: 2k<br/>combiner=true, algorithm=rrf
    activate H
    Note over H: each leg returns up to shards.rows candidates<br/>fused by RRF on the coordinator
    H-->>SR: top k in fused order (no per-doc score)
    deactivate H

    alt combined request fails (404 no /hybrid handler, 5xx, ...) or embedding fails
        SR->>S: keyword-only (edismax)
        S-->>SR: results
        opt keyword-only is empty
            SR->>S: vector-only (kNN, reusing the embedding)
            S-->>SR: results
        end
    end
    Note over SR: an interrupted request restores the interrupt flag<br/>and throws, with no fallback
```

- **No `score` on hybrid rows.** Solr orders hits by fused score, but the per-document `score`
  it returns is a raw keyword or vector score, so the repository does not request it.
- **An empty fused result is returned as is.** Fallback runs only on errors.
- **A missing `/hybrid` handler** is logged once per collection as a WARN and served by the
  fallback. The project configset (`solr-config/conf`) declares the handler.

## RAG question answering (`/ask`)

```mermaid
sequenceDiagram
    autonumber
    actor U as Client
    participant SS as SearchService.ask()
    participant M as MessageChatMemoryAdvisor
    participant PG as PostgreSQL
    participant RA as RetrievalAugmentationAdvisor
    participant QT as queryTransformer
    participant R as HybridDocumentRetriever
    participant SR as SearchRepository
    participant DF as decisionsFilter (optional)
    participant RR as Reranker
    participant D as Decisions provider
    participant C as Claude

    U->>SS: POST /ask {question, conversationId}
    SS->>M: prompt(question, conversationId)
    M->>PG: load history
    PG-->>M: earlier turns
    M->>RA: prompt + history
    RA->>QT: transform(question, history)
    alt follow-up in a named conversation
        QT->>C: CompressionQueryTransformer<br/>(search.rag.query-rewrite.model, default claude-haiku-4-5)
        C-->>QT: standalone query
        Note over QT: rewrite kept in the query context (rag.standalone)<br/>on failure: WARN, question as asked
    else first question, or the shared "default" conversation
        Note over QT: question searched as asked
    end
    QT-->>RA: query
    RA->>R: retrieve(query)
    R->>SR: executeHybridRerankSearch(top-k=20,<br/>fl=id,content,metadata_*)
    SR-->>R: candidates in RRF order
    R-->>RA: Documents
    Note over RA: pass-through joiner keeps RRF order
    opt search.rag.decisions.enabled=true and a provider is configured
        RA->>DF: process(standalone query, candidates)
        DF->>D: one call per passage: injection? relevant? answers?
        D-->>DF: probabilities
        DF-->>RA: passages that pass<br/>(a passage it cannot judge: kept, WARN)
    end
    opt search.rag.rerank.enabled (default true)
        RA->>RR: process(standalone query, candidates)
        alt search.rag.rerank.provider=claude (default)
            RR->>C: rank these candidates
            C-->>RR: ordering
        else search.rag.rerank.provider=decisions
            RR->>D: score each passage
            D-->>RR: probabilities
        end
        RR-->>RA: best top-k=5, others discarded<br/>(on any failure: RRF order)
    end
    Note over RA: context may be empty: the answer can<br/>still come from chat memory
    RA->>C: question + history + context
    C-->>RA: answer
    RA-->>M: response
    M->>PG: save turn
    M-->>SS: ChatClientResponse
    SS-->>U: {answer, conversationId,<br/>sources = IDs of documents in the prompt}
```

- **Memory runs before retrieval** even though `AiConfig.ragChatClient` lists the RAG advisor
  first: advisors run by `getOrder()`, not list position. `RagAdvisorOrderingIT` pins this.
- **Retrieval searches the rewrite, not the follow-up.** `AiConfig.queryTransformer` rewrites a
  follow-up only when the conversation has an earlier turn and is not `"default"`. The
  post-processors receive the original question from `RetrievalAugmentationAdvisor`, so
  `AiConfig.judgedAgainstTheRewrite` swaps the rewrite (`rag.standalone`) back in for them.
  `QueryRewriteTest` and `RagAdvisorOrderingIT` cover this.
- Retrieval skips `SearchService`'s Claude query-generation step: the question (or its rewrite)
  goes to Solr as is.
- **Retrieval over-fetches on purpose.** `search.rag.hybrid.top-k` (20) is a candidate count;
  screening and reranking (`search.rag.rerank.top-k`, 5) decide what reaches the prompt.
- **Decisions are pluggable and off by default.** `search.rag.decisions.provider` (default
  `typesafe`) picks the `@Configuration` that contributes the `decisionsFilter` and
  `decisionsReranker` beans; `TypeSafeDecisionsConfig` is the only one today and activates only
  with `TYPESAFE_API_KEY` set. Asking for a decisions stage without a provider logs a WARN.
- `sources` comes from `RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT`, so it lists exactly
  the documents placed in the prompt.

## Indexing

```mermaid
flowchart LR
    req["POST /api/v1/index/{c}<br/>{id?, content, metadata}"] --> IS[IndexService]
    IS -->|"no id: random UUID"| IS
    IS --> VS[SolrVectorStore.add]
    VS -->|embed content| OAI[[OpenAI]]
    VS -->|"id, content, vector,<br/>metadata_* fields"| UPD["Solr /update"]
    UPD --> TXT["copyField content → _text_<br/>(project configset)"]
```

Metadata keys are stored as `metadata_<key>` dynamic fields. The project configset copies
`content` into `_text_`, which is what the keyword leg searches. Solr's `_default` configset
does not, so on `_default` collections the keyword leg finds nothing.
