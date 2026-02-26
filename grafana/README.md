# Grafana Dashboards for AI-Powered Search

Grafana dashboard definitions for monitoring the **ai-powered-search** Spring Boot application. These dashboards cover every layer of the architecture: HTTP endpoints, AI/LLM provider calls, Solr vector store operations, RRF fusion, JVM health, and PostgreSQL connection pools.

## Dashboards

| File | UID | Focus |
|------|-----|-------|
| `01-application-overview.json` | `ai-search-overview` | Application health, uptime, total request rate, error rate, HTTP status codes, and latency percentiles across all endpoints |
| `02-search-ai-performance.json` | `ai-search-ai-perf` | Search type comparison (keyword ~100ms, semantic ~500ms, hybrid ~600ms, RAG ~2s), Anthropic prompt caching hit rate & token usage, OpenAI embedding latency & throughput, indexing pipeline |
| `03-solr-vector-store.json` | `ai-search-solr-vector` | SolrVectorStore observation metrics (add/delete/similarity search via Micrometer), KNN query performance, RRF fusion phase breakdown, Solr HTTP/2 connection health |
| `04-jvm-infrastructure.json` | `ai-search-jvm-infra` | JVM heap/non-heap memory, GC pause durations, thread counts (platform + virtual threads), HikariCP PostgreSQL pool, CPU usage, file descriptors, logback events |

## Architecture Mapped to Metrics

```
Client
  │
  ▼
┌──────────────────────────────────────────────────────┐
│  Spring Boot 4 + Actuator + Micrometer               │  ← Dashboard 01: HTTP metrics
│  (Virtual Threads enabled)                            │  ← Dashboard 04: JVM/threads
│                                                       │
│  SearchController ─► SearchService                    │  ← Dashboard 02: per-endpoint latency
│    GET /{collection}          (keyword, Claude AI)    │
│    GET /{collection}/semantic (OpenAI embed → KNN)    │
│    GET /{collection}/hybrid   (RRF: keyword + KNN)    │  ← Dashboard 03: RRF fusion breakdown
│    POST /ask                  (RAG: retrieve + gen)   │
│                                                       │
│  IndexController ─► IndexService                      │  ← Dashboard 02: indexing pipeline
│    POST /{collection}         (single doc + embed)    │
│    POST /{collection}/batch   (batch + embed)         │
│                                                       │
│  SolrVectorStore (AbstractObservationVectorStore)     │  ← Dashboard 03: vector store ops
│    add(), delete(), similaritySearch()                 │
│                                                       │
│  PromptCacheMetricsAdvisor                            │  ← Dashboard 02: cache hit/miss
│  ChatClient (searchChatClient, ragChatClient)         │  ← Dashboard 02: Claude API calls
│  EmbeddingModel (OpenAI text-embedding-3-small)       │  ← Dashboard 02: embedding latency
│                                                       │
│  HikariCP ─► PostgreSQL 16 (chat memory)              │  ← Dashboard 04: connection pool
└──────────────────────┬───────────────────────────────┘
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
   ┌───────────┐ ┌───────────┐ ┌───────────┐
   │  Solr     │ │ Anthropic │ │  OpenAI   │
   │  9.10.0   │ │ Claude    │ │ Embedding │
   │ +ZooKeeper│ │ Sonnet4.5 │ │ 3-small   │
   └───────────┘ └───────────┘ └───────────┘
```

## Prerequisites

### 1. Add Prometheus Micrometer Registry

Your `build.gradle.kts` already includes `spring-boot-starter-actuator`. Add the Prometheus registry:

```kotlin
dependencies {
    // Already present
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // ADD: Prometheus metrics export
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
}
```

### 2. Enable Actuator Endpoints

Add to `application.properties`:

```properties
# Expose Prometheus and health endpoints
management.endpoints.web.exposure.include=health,info,prometheus,metrics
management.metrics.export.prometheus.enabled=true

# Tag all metrics with application name for Grafana filtering
management.metrics.tags.application=ai-powered-search

# Enable HTTP request observation (Spring Boot 3+)
management.observations.http.server.requests.enabled=true

# Enable Spring AI observation metrics
spring.ai.chat.client.observation.enabled=true
spring.ai.embedding.observation.enabled=true
spring.ai.vectorstore.observations.include-query-response=true
```

### 3. Prometheus Scrape Configuration

Add to your `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'ai-powered-search'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 15s
    static_configs:
      - targets: ['localhost:8080']
```

### 4. Dashboards (Auto-Provisioned)

Dashboards are **automatically loaded** when the LGTM stack starts via `docker compose up`. No manual import needed.

The provisioning works via volume mounts in `docker-compose.yml`:
- `grafana/provisioning/dashboards/dashboards.yaml` → tells Grafana where to find dashboard JSONs
- `grafana/dashboards/*.json` → the 4 dashboard definitions mounted into the container

To verify: open Grafana at http://localhost:3000 (admin/admin) and all 4 dashboards will appear under **Dashboards**.

## Metric Sources

### Out-of-the-Box (Spring Boot Actuator + Micrometer)

These metrics are available automatically with the dependencies above:

| Metric | Source |
|--------|--------|
| `http_server_requests_seconds_*` | Spring Boot HTTP observations |
| `jvm_memory_used_bytes`, `jvm_memory_committed_bytes`, `jvm_memory_max_bytes` | JVM memory |
| `jvm_gc_pause_seconds_*` | GC pauses |
| `jvm_threads_live_threads`, `jvm_threads_states_threads` | Thread monitoring |
| `process_cpu_usage`, `system_cpu_usage` | CPU |
| `hikaricp_connections_*` | HikariCP pool (PostgreSQL) |
| `logback_events_total` | Log events by level |

### Spring AI Observations (Requires `spring.ai.*.observation.enabled=true`)

| Metric | Source |
|--------|--------|
| `spring_ai_chat_client_call_seconds_*` | ChatClient API call duration |
| `spring_ai_embedding_model_call_seconds_*` | Embedding generation duration |
| `spring_ai_embedding_model_token_usage_total` | Embedding token counts |
| `spring_ai_vector_store_query_seconds_*` | VectorStore similarity search |
| `spring_ai_vector_store_add_seconds_*` | VectorStore document add |
| `spring_ai_vector_store_delete_seconds_*` | VectorStore document delete |
| `ai_chat_client_token_usage_total` | Token usage by type (input, output, cache_*) |

### Custom Metrics (Requires Instrumentation)

The Solr query internals and RRF fusion panels reference custom metrics that you would need to add with Micrometer timers. Example implementation:

```java
@Repository
public class SearchRepository {

    private final MeterRegistry meterRegistry;

    // In your keyword/vector/hybrid search methods:
    Timer.Sample sample = Timer.start(meterRegistry);
    // ... execute Solr query ...
    sample.stop(Timer.builder("solr.query")
        .tag("type", "keyword")  // or "knn", "hybrid_keyword", etc.
        .register(meterRegistry));

    // For RRF merge timing:
    sample.stop(Timer.builder("rrf.merge")
        .register(meterRegistry));
}
```

## Quick Start

| Step | Action |
|------|--------|
| 1 | Add `micrometer-registry-prometheus` dependency |
| 2 | Enable actuator properties (see above) |
| 3 | Configure Prometheus scraping |
| 4 | Run `docker compose up -d` — dashboards are auto-provisioned |
| 5 | Open http://localhost:3000 (admin/admin) |
| 6 | (Optional) Add custom Micrometer timers for Solr/RRF internals |

## Notes

- All dashboards use `job="ai-powered-search"` as the Prometheus label filter — adjust if your job name differs
- The `SolrVectorStore` extends `AbstractObservationVectorStore` which auto-registers vector store observation metrics when Spring AI observations are enabled
- The `PromptCacheMetricsAdvisor` currently logs to SLF4J — to surface cache metrics in Prometheus, register a `Counter` for `ai_chat_client_token_usage_total` with `type` tags for `cache_creation`, `cache_read`, `input`, `output`
- Virtual threads (Java 21+) may not show distinct thread count growth since they're managed by the JVM carrier thread pool — monitor `jvm_threads_started_threads_total` for activity
