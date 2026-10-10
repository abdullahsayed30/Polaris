# Operational readiness

This guide turns the repository's health, metrics, tracing, logging, and deployment artifacts into an operating model. The targets below are recommendations for a first production assessment; they are not measured results or guarantees from this repository.

## User journey and ownership

The business-critical journey is order placement through the gateway, synchronous stock reservation over gRPC, and asynchronous notification processing over Kafka. Operational ownership should follow that flow:

| Concern | Primary signal | Likely owner |
| --- | --- | --- |
| Public API access and authentication | Gateway availability, 4xx/5xx rate, latency | Edge/API team |
| Order acceptance and persistence | Order HTTP outcomes, database health | Order team |
| Stock decision and reservation | gRPC outcomes/latency, inventory database health | Inventory team |
| Event delivery and notification | Consumer lag, persistent inbox status, retries, DLQ growth | Messaging/notification team |
| Shared dependencies | PostgreSQL, Kafka, Redis, OIDC, OTLP service health | Platform team |

Every alert should link to an owned runbook and dashboard, identify the environment and service, and avoid paging on a single failed probe.

## Proposed service-level objectives

| Indicator | Initial objective | Measurement note |
| --- | --- | --- |
| Authenticated order API availability | 99.9% good requests over 30 days | Count expected API responses as good; exclude client-caused 4xx and planned maintenance by policy |
| Order creation latency | 95% below 750 ms over 30 days | Measure at the gateway and keep an internal order-service view for diagnosis |
| Order read latency | 95% below 300 ms over 30 days | Separate from create because the latter includes inventory RPC work |
| Inventory gRPC availability | 99.95% non-system-error calls over 30 days | Business rejections such as insufficient stock are valid outcomes, not outages |
| Notification freshness | 99% processed within 60 seconds over 30 days | Completed-event age is instrumented; unseen broker backlog still needs broker/exporter telemetry |

The application exposes HTTP/gRPC metrics, committed pending/outbox/inbox snapshots and after-commit simulated-notification observations. Process-local completion/attempt counters are not durable lifetime totals or broker queue depth. These signals do not establish a measured SLO, reservation correctness or external notification delivery. Broker backlog remains an instrumentation gap.

## Evaluated local alerts

Prometheus loads `deploy/prometheus/rules/polaris-alerts.yml` through `rule_files`; Compose mounts that directory read-only. Inspect pending/firing states at [local Prometheus Alerts](http://localhost:9090/alerts). Rule evaluation provides local visibility only: no Alertmanager, on-call receiver or external email/Slack/webhook notification is configured. Prometheus itself being unavailable requires an independent monitoring path.

These are initial **demo operational thresholds**, not measured SLOs, error-budget burn rates or production paging policy. An operator must calibrate volume, age and persistence against actual traffic, recovery backoff and service objectives before deploying them. Burn-rate rules require an agreed good-request indicator, measurement policy and observation window first.

| Alert | Condition / persistence | Response |
| --- | --- | --- |
| `PolarisServiceScrapeUnavailable` | No successful target or missing required job / 2m | [Scrape reachability](runbooks/service-reachability.md); confirm customer impact separately |
| `PolarisBusinessTelemetryUnavailable` | No complete, successful, non-future snapshot younger than 45s among reachable replicas, or missing required freshness bucket / 2m | [Telemetry](runbooks/business-telemetry.md); a hidden backlog is not empty |
| `PolarisOrderApiSystemErrors` | More than 5% HTTP 5xx among eligible order requests; at least 20 per 5m / sustained 5m | [Order path](runbooks/order-path.md); excludes 4xx and actuator requests |
| `PolarisInventoryRpcSystemErrors` | More than 5% system errors among eligible `ReserveStock` client calls; at least 20 per 5m / sustained 5m | [Order path](runbooks/order-path.md); excludes expected input/precondition/auth rejections |
| `PolarisPendingOrdersStalled` | Committed pending count above zero and oldest age over 120s / 2m | [Durable delivery](runbooks/durable-delivery.md); preserve reservation identity |
| `PolarisOutboxStalled` | Committed pending/retry rows with oldest age over 120s / 2m | [Durable delivery](runbooks/durable-delivery.md); includes scheduled backoff |
| `PolarisOutboxFailed` | Retained committed `FAILED` row / 1m | [Durable delivery](runbooks/durable-delivery.md); terminal work needs an explicit replay decision |
| `PolarisNotificationFailures` | New failed/acknowledged DLQ sends or committed dead-letter observations in 5m / 1m | [Notification](runbooks/notification-processing.md); attempts are not unique failures or DLQ depth |
| `PolarisNotificationProcessingLate` | More than 5% of at least 20 completed samples per 5m took over **one hour** / sustained 5m | [Notification](runbooks/notification-processing.md); completed simulated processing only |

Backlog rules gate each replica by **its own** scrape, success, timestamp and required gauge presence before `max by(job)` aggregation. Fresh replicas cannot validate stale values from failed replicas. Shared database row gauges are never summed across replicas. A snapshot failure can suppress a backlog alert and raise the telemetry alert instead; that transition is not business recovery. A single scrapeable/fresh replica keeps the corresponding service-level reachability/telemetry alert inactive.

HTTP rules use numeric status and route/URI labels from actual instrumentation. The gRPC rule uses only the starter client `grpc_client_processing_duration_seconds_count` family with verified `service`, `method`, `methodType`, `statusCode` labels; it does not add the Observation family or server calls to the same denominator. No traffic or fewer than 20 eligible calls suppresses ratio alerts, so independent reachability and snapshot alerts remain necessary. Missing request series without prior traffic cannot establish customer-path health.

Notification delay uses the explicitly instrumented `le="3600.0"` bucket versus completed histogram count, rather than a potentially censored p95. Seven-day overflow is included in the slow fraction and remains visible in the Business Flow dashboard's overflow panel. A missing one-hour bucket raises telemetry warning instead of manufacturing zero delay. The proposed 60-second SLO above is not this one-hour demo threshold; there is no explicitly configured 60-second histogram boundary. No completed events means no delay estimate, not proven freshness. Unseen Kafka backlog, external provider delivery and automatic DLQ redrive remain unimplemented.

Alerts carry `environment` from Prometheus external labels (`docker-demo` here), `service`, severity and a `*-owner` responsibility placeholder. HTTP/gRPC system errors and terminal outbox failures are `critical`; other conditions are `warning`. These labels do not establish a real on-call roster. Replace environment, owner mapping, local Grafana URLs and canonical repository runbook URLs for the target deployment. Runbook URLs point to main and become available there when this change is integrated; dashboard UIDs match provisioned files.

## Repeatable alert evidence

```bash
bash deploy/scripts/validate-alerts.sh
./mvnw -B -ntp spotless:check checkstyle:check test
./mvnw -B -ntp -DskipTests package
python3 deploy/scripts/demo-alerts.py
```

The existing required CI quality job runs this rule validation as a blocking step; a failure prevents `CI required` from passing. The rule runner uses the same Prometheus 2.55.1 evaluator as Compose, with no network, host ports or persistent resources. Its tests cover healthy/pending/firing/resolved states, brief interruptions, request-volume guards, client/business rejections, missing/stale/future/NaN snapshot data, surviving replicas, a failed stale replica beside a healthy one, missing freshness buckets and seven-day overflow. Synthetic series prove rule behavior, not application recovery.

The Python demo creates an unpredictable unique Compose project from `deploy/alert-demo/compose.yml`, with its own network/disposable volumes, loopback-only dynamic ports, CPU/memory limits and non-root application processes. It mounts current packaged application JARs and unchanged production alert rules, records source commit/JAR/rule hashes, queries actual emitted metric signatures, places an authenticated order, stops **only its inventory fixture**, observes scrape-loss and pending-order alerts, starts that fixture and verifies committed recovery/idempotent replay of the original order. Normal alert thresholds and durations are not accelerated. Tracing export is disabled in this smaller fixture; notification delivery remains simulated. Local Keycloak credentials/password grant are development fixtures. No receiver sends external messages.

Evidence defaults to a new `target/alert-demo/` directory and includes UTC timestamps, pending/firing/recovered alert/rule/query snapshots, committed order checks and cleanup state. The script removes only its generated project in `finally`, including volumes; never point it at an existing stack. It does not touch paused review containers. See [retained proof](alert-evidence/2026-10-10/README.md) for the executed scenario and its limits. Other alert paths require additional fault demonstrations before claiming live coverage.

## Triage sequence

1. Confirm customer impact at the gateway: request rate, status family, latency, and affected route.
2. Follow `X-Request-Id`, `traceId`, and `spanId` through structured logs and traces.
3. Separate application saturation from dependency failure by checking ready replicas, restarts, CPU/memory, database pools, Kafka lag, Redis, and OIDC/JWKS reachability.
4. For create-order failures, inspect the order-to-inventory gRPC span and distinguish valid stock rejection from transport/system failure.
5. For delayed notifications, preserve consumer-group and DLQ evidence before replaying or changing offsets.
6. Mitigate with scaling, dependency recovery, traffic controls, or a known-compatible Helm rollback. Do not roll back across an incompatible Liquibase change.
7. Record impact, timeline, detection gap, and follow-up owner after recovery.

Useful commands:

```bash
kubectl -n polaris get deploy,pod,hpa,pdb
kubectl -n polaris describe pod POD_NAME
kubectl -n polaris logs POD_NAME --since=20m
kubectl -n polaris get events --sort-by=.lastTimestamp
helm history polaris -n polaris
```

## Capacity, scaling, and disruption

Resource requests and limits in the chart are safe starting hypotheses, not load-test results. Establish a representative order mix, measure saturation and tail latency, then revise requests before enabling HPAs. CPU/memory HPAs cannot directly protect Kafka freshness; consumer lag-based scaling would require an external metrics adapter or event-driven autoscaler that is not included.

The default two replicas and `minAvailable: 1` PDBs preserve one pod during voluntary disruption. They do not protect against a zone failure unless scheduling constraints and cluster capacity place replicas across zones. Configure topology spread after validating node labels in the target cluster.

## Data protection and recovery

Pending-order recovery runs automatically using persisted order identities (ADR 0020). Inspect `orders.status = 'PENDING'`, `reservation_retry_at`, recovery logs, and the `polaris.reservation.recovery` counter outcomes when orders stop progressing. The counter measures attempts, not unique orders or a business SLO. Persistent failures need investigation; the worker deliberately keeps retrying rather than releasing stock on an uncertain result. Never manually delete the pending order or change its items to resolve a timeout. Clients retry with the original idempotency key and payload; headerless HTTP retries create separate orders.

The platform owner must define backup frequency, point-in-time recovery, restore tests, encryption, and retention for each PostgreSQL database. Kafka topic retention, replication, ACLs, and replay policy also remain external. Application recovery is incomplete until database restore and event replay have been rehearsed together in a non-production environment.

## Release evidence

For each release retain the image digests, rendered Helm manifests, chart version, values provenance excluding secrets, test results, migration review, and rollback decision. The repository currently provides a local chart validation command but no image/chart publication or cluster promotion workflow.
