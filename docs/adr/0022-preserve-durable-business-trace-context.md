# 0022 - Preserve Durable Business Trace Context

Date: 2026-10-06

## Status

Accepted on 2026-10-10. The owner approved full business-flow propagation through durable recovery, database persistence blocks, outboxes, Kafka and simulated notification processing. Extends ADRs 0018, 0019 and 0020; retains ADR 0021 dependency direction. Acceptance records the architecture decision, not production delivery or deployment readiness.

## Context

HTTP/gRPC observations and Kafka producer/listener observations already exist, but an outbox scheduler and a restarted recovery worker have no in-memory request context. An event ID or a trace ID alone cannot restore parentage, sampling or trace state. The business flow also needs visible storage operations and truthful commit/rollback outcomes.

## Decision

Reuse Spring Boot's managed OpenTelemetry SDK behind Micrometer Tracing. Store the configured bounded W3C carrier (`traceparent`, `tracestate`, including parent identity and sampling flags) and a capture timestamp alongside the original pending order and each Order/Inventory outbox row. No baggage fields are in the current service propagation policy; arbitrary baggage, authorization headers, SQL text and SQL bind values must not be stored or attached to custom spans. The adapter also supports the existing composite propagator's recognized B3 fields if configured; it never hand-builds a trace ID as a carrier.

The nullable columns are added through new Liquibase changesets. Capture joins the existing business transaction. Order updates and later HTTP retries preserve the original carrier; it is not copied into the pure business model. Event payloads, stable event identities, money values and protobuf contracts remain unchanged.

Restore the immutable carrier before locking/resolving a pending order and before every outbox publication attempt. The managed Micrometer current-trace-context bridge scopes the restored native span, so observed gRPC/Kafka sends use the attempt as parent even while a scheduler Observation is active. A native OpenTelemetry scope alone leaves the bridge-specific ambient context key behind and is insufficient. Single-event work continues the original trace and parent regardless of carrier age; a different ambient scheduler or HTTP retry is linked to the durable attempt. Retention belongs to the tracing backend and does not change propagation identity. Missing/invalid/oversized context starts a new trace safely. Unsampled context remains unsampled at every age and does not invent an exported trace. No request span remains open while work waits in storage or Kafka.

Each recovery/publish/handler attempt has its own span. Infrastructure-owned CLIENT spans wrap key JPA persistence blocks using fixed database/table/operation attributes. They measure adapter execution, potentially containing several SQL statements, not individual SQL timings or PostgreSQL internals. The immutable-carrier bootstrap read precedes the recovered scope and is traced under the incoming or scheduler context; the locked business read is traced under the restored durable context. Workflow/outbox creation spans obtain `committed` or `rolled_back` from Spring transaction synchronization. Query execution is not proof of transaction commit, Kafka broker acknowledgement is not proof of inbox commit, and simulated notification handling is not external provider delivery.

Database aggregate gauges are sampled off the request/scrape path. Initial values are missing, refresh failure retains the last valid snapshot and exposes failure/staleness, and replica views of the same database must not be summed. Process-local counters measure attempts or after-commit observations as documented, not durable lifetime business totals. Committed duplicate inbox entries do not produce another completion observation.

Tracing adapters stay under `adapter.out.observability`; persistence bookkeeping stays under `adapter.out.persistence`. No tracing framework dependency is permitted in application services, pure models or ports. Telemetry capture/restoration/export failure must not fail business delivery. Actual storage failure still fails the existing business transaction.

## Consequences

Durable work remains diagnosable across delay and application restart without changing reservation, idempotency, transaction, outbox/inbox or acknowledgement semantics. Some instrumentation is intentionally service-local to preserve runtime independence; it does not belong in `shared`. Old rows without context remain processable, and old application versions can ignore the new nullable columns. Keep these columns on rollback rather than deleting recovery intent.

The local stack still has no log storage datasource or production DLQ consumer. ECS logs can be matched to Tempo by trace/span IDs; controlled replay preserves event identity and propagation headers. Replaying a committed event is traced as suppressed. Multi-message/multiple-parent processing requires links rather than assigning one false parent; the current Spring Kafka 3.3 listener is single-record and is not replaced by a batch listener.

## References

- [Spring Boot 3.5 tracing](https://docs.spring.io/spring-boot/3.5/reference/actuator/tracing.html)
- [Spring Kafka 3.3 observations](https://docs.spring.io/spring-kafka/reference/3.3/kafka/micrometer.html)
- [OpenTelemetry propagation](https://opentelemetry.io/docs/concepts/context-propagation/)
- [Messaging parent/link semantics](https://opentelemetry.io/docs/specs/semconv/messaging/messaging-spans/)
- [Database span conventions](https://opentelemetry.io/docs/specs/semconv/db/database-spans/)
