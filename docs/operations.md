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
| Notification freshness | 99% processed within 60 seconds over 30 days | Requires broker lag and event-age telemetry not currently bundled |

The current application exposes generic HTTP, JVM, Kafka-client, and gRPC instrumentation. It does not yet expose a durable business counter for orders accepted, reservation correctness, notification completion, or DLQ size. Do not claim those business SLOs are observable until custom metrics or broker/exporter metrics are added.

## Alert recommendations

Start with multi-window burn-rate alerts for the order API availability SLO rather than static error thresholds. A common first pass is a fast page when both a short and long window burn rapidly, and a ticket for slower sustained burn. Validate query names against the deployed Micrometer version before committing recording rules.

Page-worthy conditions:

- Order API fast-burn consumption of the availability error budget.
- No ready gateway or order-service replicas for several minutes.
- Sustained inventory gRPC system errors causing order creation failures.
- Kafka consumer lag or oldest-message age threatening the notification freshness objective.
- Database connection exhaustion, loss of primary availability, or Liquibase startup failure during rollout.

Ticket or warning conditions:

- Slow error-budget burn.
- p95/p99 latency regression without material errors.
- CPU throttling, memory approaching the container limit, repeated OOM kills, or HPA saturation at max replicas.
- Pods unavailable during a rollout, repeated restarts, or PDBs preventing planned maintenance.
- DLQ growth above zero after excluding an acknowledged test.
- Trace export failures; these should not by themselves make the customer path unavailable.

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
