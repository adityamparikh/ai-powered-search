# Observability

What the running application emits, where it goes, and how to query it from a shell, which is
how an agent reads it. Routed from CLAUDE.md. Dashboard descriptions are in
[grafana/README.md](../grafana/README.md).

## What is emitted

- **Metrics, traces and logs over OTLP** to `${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}`
  (`management.otlp.*` in `application.properties`), every 10 s for metrics, every request traced
  (`management.tracing.sampling.probability=1.0`).
- Micrometer observations for HTTP requests (`http_server_requests_seconds_*`), the Spring AI chat
  and embedding clients, and `SolrVectorStore` (it extends `AbstractObservationVectorStore`).
- `PromptCacheMetricsAdvisor` logs a `[Prompt Caching]` line per Claude call with cache reads and
  writes; the RAG post-processors log `WARN` when they degrade (rewrite failed, passage not judged,
  rerank fell back to RRF order).
- `GET http://localhost:8080/actuator/health` for liveness.

The service name, and so the Prometheus `job` label and the Loki `service_name` label, is
`spring.application.name` = `ai-powered-search`.

## Where it goes: the `lgtm` service

`docker compose up -d` starts `grafana/otel-lgtm` with Grafana, Prometheus, Loki and Tempo. No
login is needed locally (the image enables anonymous admin access; the admin user is
`admin`/`admin`).

| Service | URL | Query from a shell |
|---|---|---|
| Grafana | http://localhost:3000 | `curl -s localhost:3000/api/search?type=dash-db` lists dashboards |
| Prometheus | http://localhost:9090 | `curl -s 'localhost:9090/api/v1/query' --data-urlencode 'query=sum(rate(http_server_requests_seconds_count{job="ai-powered-search"}[5m])) by (uri,status)'` |
| Loki | http://localhost:3100 | `curl -s -G localhost:3100/loki/api/v1/query_range --data-urlencode 'query={service_name="ai-powered-search"} \|= "WARN"' --data-urlencode 'limit=50'` |
| Tempo | http://localhost:3200 | `curl -s 'localhost:3200/api/search?tags=service.name%3Dai-powered-search&limit=20'`, then `curl -s localhost:3200/api/traces/<traceID>` |
| OTLP in | localhost:4317 (gRPC), localhost:4318 (HTTP) | where the app sends everything |

Useful Prometheus queries (the same ones the dashboards plot):

```promql
# p95 latency per endpoint
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{job="ai-powered-search",uri=~"/api/v1/.*"}[5m])) by (le, uri))
# 5xx ratio
sum(rate(http_server_requests_seconds_count{job="ai-powered-search",status=~"5.."}[5m]))
  / sum(rate(http_server_requests_seconds_count{job="ai-powered-search"}[5m]))
```

List metric names when a query returns nothing (names differ between OTLP and the Prometheus
registry): `curl -s localhost:9090/api/v1/label/__name__/values | tr ',' '\n' | grep -i <word>`.

## Alerts

`grafana/provisioning/alerting/ai-powered-search.yaml` provisions two rules into the `lgtm`
service, folder `ai-powered-search`:

| Rule | Fires when |
|---|---|
| `ai-search-http-5xx-ratio` | more than 5% of requests return 5xx for 5 minutes |
| `ai-search-ask-p95-latency` | `POST /api/v1/search/ask` p95 latency is above 10 s for 10 minutes |

```bash
curl -s localhost:3000/api/v1/provisioning/alert-rules          # the rules as provisioned
curl -s localhost:3000/api/prometheus/grafana/api/v1/alerts      # what is firing now
```

No contact point is configured, so alerts show in Grafana and through the API but notify no one.
Add one under `grafana/provisioning/alerting/` when the app runs somewhere a person should be
paged. `GrafanaLgtmIntegrationTest` provisions the rules file into a real LGTM container and fails
if Grafana does not load both rules.
