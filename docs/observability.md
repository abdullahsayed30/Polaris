# Observability

Polaris keeps local observability close to production conventions without requiring a managed backend. Services expose actuator health and Prometheus metrics, emit ECS JSON logs, and can export OpenTelemetry traces to Tempo.

See the [retained local evidence](observability-evidence/2026-10-06/README.md) for actual Grafana/Tempo screenshots, original-trace recovery/outbox/replay checks, committed DB state, runtime JAR provenance and fixture limitations. Synthetic old-carrier tests establish age semantics without claiming hours of elapsed runtime.

## Tracing

All runtime services include Micrometer Tracing with the OpenTelemetry bridge and OTLP exporter. The default application configuration sets sampling to `1.0` and disables trace export:

| Property | Default | Purpose |
| --- | --- | --- |
| `POLARIS_TRACING_EXPORT_ENABLED` | `false` | Enables or disables OTLP trace export |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | `http://localhost:4318/v1/traces` | OTLP HTTP trace endpoint |
| `POLARIS_TRACING_SAMPLING_PROBABILITY` | `1.0` | Trace sampling probability |
| `POLARIS_DEPLOYMENT_ENVIRONMENT` | `local` | OpenTelemetry resource attribute |

Docker Compose sets `POLARIS_TRACING_EXPORT_ENABLED=true` and sends traces to `http://tempo:4318/v1/traces`.

## Request Correlation

The gateway accepts or creates `X-Request-Id`, returns it to the caller, and forwards it upstream. `order-service` stores that value in MDC as `request.id`; the gRPC client interceptor propagates it as `x-request-id` metadata to `inventory-service`. The inventory gRPC server interceptor restores the same value into MDC while handling the RPC.

## Logs

Services use Spring Boot structured console logging with Elastic Common Schema. Logs include Boot-managed `traceId` and `spanId` when tracing context exists. Polaris request and gRPC access logs also include the request ID in the message and MDC field `request.id`.

## Metrics and Dashboards

Prometheus scrapes `/actuator/prometheus` for gateway, order, inventory, and notification services. Grafana provisions:

| Dashboard | File | Purpose |
| --- | --- | --- |
| Polaris Overview | `deploy/grafana/dashboards/polaris-overview.json` | HTTP traffic, service scrape health, JVM, CPU, and gRPC summary panels |
| Polaris Business Flow | `deploy/grafana/dashboards/polaris-business-flow.json` | Pending recovery, outboxes, notification outcomes and snapshot health |
| Polaris gRPC | `deploy/grafana/dashboards/polaris-grpc.json` | gRPC server/client request rates, latency, and errors |

## gRPC Health and Reflection

`inventory-service` exposes gRPC health checks through the starter health service. Reflection is disabled by default and enabled only in `local` and `docker` profiles so local tools can inspect the internal protobuf contract without making reflection a production default.

## Full Business Flow

[ADR 0022](adr/0022-preserve-durable-business-trace-context.md) extends existing Micrometer/OpenTelemetry HTTP, gRPC and Kafka observations through pending recovery and both outboxes. The managed baseline is Spring Boot 3.5.16, Micrometer 1.15.12, Micrometer Tracing 1.5.12 and Spring Kafka 3.3.16. Starter-generated gRPC metric families and Observation-generated families can coexist: dashboards use the verified `grpc_*_processing_duration_seconds_*` family with the live labels `service`, `method`, `methodType` and `statusCode`; they must not sum both families.

The original pending-order carrier is committed before remote reservation. Outbox recording captures the complete configured W3C carrier, not a trace ID alone. Restored attempts propagate via observed gRPC/Kafka sends. A normal trace includes HTTP, intent creation, order resolution, inventory RPC, database persistence blocks, event creation, outbox publishing, Kafka consumption and simulated notification processing. Handlers/retries and DLQ publication have separate attempt spans. A notification duplicate is recorded as `suppressed`, without another completion.

Custom database CLIENT spans have fixed `db.system.name=postgresql`, `db.operation.name` and `db.collection.name`. They wrap adapter persistence blocks, including lazy mapping and flush where applicable; they are not individual SQL statements. SQL/bind values/credentials and arbitrary baggage are excluded. Transactional stages separately report `polaris.transaction=committed|rolled_back` after completion; an executed query or acknowledged Kafka send may still belong to a rolled-back local transaction.

Valid durable context resumes under its original trace and parent regardless of age, with a link to a different ambient scheduler or HTTP retry. This does not extend Tempo's 24-hour retention: the original request spans may have expired even while later spans retain their trace identity. Missing/invalid/oversized context safely starts a new trace and does not change event payload validation. Sampling flags are preserved, and unsampled/unexported spans cannot be retrieved. Telemetry outages do not change business success or source acknowledgement policy.

## Metric Contract

Prometheus collects metrics; Grafana reads its datasource data. The Business Flow dashboard complements HTTP/gRPC and JVM panels.

| Prometheus series | Type / unit | Meaning and labels |
| --- | --- | --- |
| `polaris_pending_orders` | Gauge / orders | Committed `PENDING` rows, including not-yet-due recovery |
| `polaris_pending_order_oldest_age_seconds` | Gauge / seconds | Age since creation of the oldest committed pending order; zero for a successfully sampled empty set |
| `polaris_outbox_events{status}` | Gauge / rows | Retained committed rows in `PENDING`, `RETRY`, `FAILED`, `PUBLISHED` |
| `polaris_outbox_oldest_age_seconds` | Gauge / seconds | Age since creation of oldest `PENDING`/`RETRY` row, including backoff; `FAILED` is separate |
| `polaris_outbox_publications_total{outcome}` | Counter / attempts | `published`, `retry`, `failed`; incremented on transport attempt, independent of subsequent database commit |
| `polaris_notification_inbox_events{status}` | Gauge / rows | Retained committed `PROCESSING`, `PROCESSED`, `DEAD_LETTERED` rows; not broker lag |
| `polaris_notification_completions_total{event_type,outcome}` | Counter / observations | After-commit `processed`/`dead_lettered` observations; duplicates and rollback excluded; process-local, not a durable lifetime total |
| `polaris_notification_freshness_seconds_*{event_type}` | Histogram / seconds | Event occurrence to committed simulated processing; finite buckets through 7 days; completed events only, duplicates excluded; future occurrence timestamps excluded and counted separately |
| `polaris_notification_freshness_overflow_total{event_type}` | Counter / observations | After-commit processed events older than 7 days; included in histogram count/sum and infinite bucket, without another completion |
| `polaris_notification_retries_total` | Counter / attempts | Handler retry scheduling, excluding the first handler attempt |
| `polaris_notification_dlq_publications_total{outcome}` | Counter / attempts | Broker `acknowledged`/`failed` sends, including poison/redelivery; not DLQ depth or unique dead-letter events |
| `polaris_notification_duplicates_total` | Counter / deliveries | Suppressed inbox duplicates, without another simulated handler completion |
| `polaris_notification_invalid_occurrence_time_total` | Counter / observations | Successfully processed events with a future occurrence time; no fabricated freshness sample |
| `polaris_observability_snapshot_success` | Gauge / boolean | Last refresh succeeded (1) or failed/not initialized (0) |
| `polaris_observability_snapshot_timestamp_seconds` | Gauge / epoch seconds | Last successful sample timestamp; missing until first success |

Snapshots refresh every 15 seconds by default (`polaris.observability.snapshot.interval`, milliseconds), never on the metrics scrape. Initial business gauges are `NaN`. Failed refresh retains values and timestamp and sets success to zero. The backlog panels require snapshot success and age below 45 seconds. Always inspect success and age alongside them; stale/missing data is not an empty backlog. Replicas read the same service database: use `max by(job,status)` for retained row gauges, not a sum across replicas. Process-local counters may be summed by service with reset-aware `rate`/`increase`, but crashes can lose after-commit observations. Retention/deletion affects row totals; they are not monotonic business counters.

Freshness includes finite boundaries at 1, 6 and 12 hours, then 1, 3 and 7 days. The p95 panel excludes replicas without the 7-day bucket during a rolling upgrade and hides an event-type series when there are no completed observations or more than 5% of its samples exceed that bucket. Prometheus otherwise returns the highest finite boundary for a quantile in the infinite bucket, which would understate long delays. Inspect the adjacent overflow observations panel when p95 is absent; missing/censored p95 is not zero freshness. Overflow increments only after committed simulated processing, excluding duplicates, rollback, dead-lettered outcomes and future occurrence timestamps. These counters remain process-local.

Labels are bounded event types/statuses/outcomes, URI templates, route IDs and RPC methods. Prometheus adds job/instance. Customer/order/event/correlation IDs never become metric labels. Inventory insufficient-stock decisions are valid business outcomes, not system errors. Notification remains simulated. Completed-event freshness alone cannot detect messages not yet seen by the consumer; broker lag/depth instrumentation remains a separate gap.

## Navigating Traces and Logs

Grafana dashboards link to Tempo Explore. Search by an observed trace ID or by `span.polaris.identity` (order/event ID) and expand HTTP/gRPC, persistence, publishing and notification attempts. Inspect transaction attributes before interpreting a completion. Match `traceId`, `spanId` and `request.id` in ECS JSON logs; event/order IDs complement propagation.

There is no Loki/Elasticsearch log datasource in this stack, so Tempo-to-log one-click queries are not provisioned. For local logs, retain the ECS output and filter the same trace ID:

```bash
docker compose logs --no-log-prefix order-service inventory-service notification-service gateway   | jq -R 'fromjson? | select(.traceId == "TRACE_ID")'
```

Controlled Kafka replay must preserve the event ID/payload and propagation headers. A committed inbox event is suppressed; replaying a metadata-less legacy event at another offset retains the existing legacy identity limitation. There is no production DLQ consumer or automatic redrive service. DLQ publication failure must still escape without source acknowledgement.

## Verification Evidence

Live scrape/query results, transaction/restart results, trace IDs and retained screenshots are recorded here after the isolated demonstration. Provisioned JSON alone is not live verification. The retained 2026-10-06 fixture queried the earlier 24 expressions; it does not validate the later 7-day freshness gate or overflow panel. Those changes have a real Prometheus-registry occurrence-age regression and separate PromQL test cases under `deploy/prometheus/tests/notification-freshness.test.yml`; check exact dashboard-query parity with `python3 deploy/prometheus/tests/validate-notification-freshness.py`, then run `promtool test rules` against that file using the pinned Prometheus image before claiming query validation.
