# Pending orders or outbox delivery need attention

Alerts: `PolarisPendingOrdersStalled`, `PolarisOutboxStalled`, `PolarisOutboxFailed`. Owner: the affected order/inventory service owner with messaging/platform support.

## Meaning and diagnosis

Pending orders or pending/retry outbox rows have an oldest committed age over 120 seconds for two minutes, or a committed terminal `FAILED` outbox row persists for one minute. Age includes not-yet-due recovery/backoff; these are demo thresholds to calibrate against retry policy. Rows are service-owned database snapshots; gate each instance by its own fresh successful snapshot, then use a maximum across replicas.

1. Open Polaris Business Flow and check snapshot validity before interpreting count/age. Capture service, UTC times, status, attempts, last error and next retry time from that service's authorized diagnostics.
2. For orders, inspect inventory availability, reservation RPC deadlines, stable reservation identity and recovery scheduling. For outboxes, inspect Kafka connectivity/topic policy/acknowledgements and database delivery-record updates.
3. Preserve event IDs, stored payloads and trace carriers. Publication counters describe transport attempts; a broker acknowledgement can precede a rolled-back database status update and a later duplicate.

## Mitigation and resolution

Restore the failed dependency and observe existing scheduled retries. Pending orders must recover forward using their persisted identity; never delete/change the pending intent or blindly release stock after a timeout. Preserve idempotency binding and original request payload.

Terminal `FAILED` outbox rows are not automatically retried when Kafka returns. Diagnose and obtain an explicit controlled replay/requeue decision, preserve the same event identity/payload, and verify consumer inbox deduplication. This repository does not provide an automatic redrive command; do not improvise broad database updates or delete failed rows just to suppress the alert.

Confirm the original order's committed final state or the original outbox's publication/approved terminal handling, fresh telemetry, a drained/healthy backlog and inactive alerts. Telemetry failure suppressing a backlog rule is not recovery. Record any terminal rows that remain and their owner.
