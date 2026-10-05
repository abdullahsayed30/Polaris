# 0019 - Use Transactional Outbox and Consumer Inbox

Date: 2026-09-20

## Status

Accepted. Package placement for the three business services is updated by [ADR 0021](0021-adopt-hombergs-hexagonal-service-structure.md); the behavioral decision remains in force.

## Context

Publishing a Kafka message after committing a service database transaction leaves a failure window: the business state can commit while the process or broker is unavailable before the message is durably accepted. Kafka delivery is also at least once, so a producer can publish successfully and crash before recording success, causing a later retry to deliver the same event again. A notification consumer must not silently commit its source offset when publication to its dead-letter topic fails.

## Decision

`order-service` and `inventory-service` store each state-changing event in an `outbox_events` row in the same local transaction as the aggregate change. A scheduled publisher locks due rows, waits for Kafka acknowledgement, and records `PUBLISHED`, `RETRY`, or terminal `FAILED` delivery state with attempt count, next attempt time, and the last error. Publisher metrics and structured logs expose each outcome. Producer idempotence and `acks=all` reduce broker-side duplicates, but delivery remains at least once.

Every shared event carries immutable metadata: a UUID event ID, contract version, occurrence time, correlation ID, and optional causation ID. A retry republishes the stored payload rather than regenerating metadata.

Application services record events through application-owned ports. Messaging adapters implement serialization and outbox recording; the entities and Spring Data repositories belong in `persistence`, and typed properties in `config`. Actual broker delivery happens only after the business transaction commits.

`notification-service` persists an inbox row keyed by event ID in the same database transaction as its processing result. A committed `PROCESSED` or `DEAD_LETTERED` row makes redelivery a no-op. Handler failures are retried in-process; exhausted or malformed events are acknowledged only after the dead-letter producer future completes successfully. A dead-letter publication failure escapes the listener and is retried indefinitely by the Kafka container without acknowledging the source record.

The Kafka adapter decodes transport records into plain delivery data and delegates to the application service, which owns the transaction, deduplication, retries, and processing outcome. Persistence entities do not depend on Kafka record types. Dead-letter publication is an application port implemented by the Kafka adapter.

For retained events emitted before metadata was introduced, notification accepts an absent metadata member and derives a deterministic legacy identity from the source topic, partition, and offset, preserving `createdAt` or `adjustedAt` as occurrence time. Explicit but malformed metadata is not treated as legacy. Deploy this compatible consumer before metadata-emitting producers. See [contract compatibility](../../contracts/README.md) and [deployment](../deployment.md).

## Consequences

Committed order and inventory changes retain a durable event record through Kafka outages. The outbox can produce duplicates if Kafka accepted a message but the database status update did not commit, so consumers still need idempotency. The notification inbox protects its database workflow, but a real email, SMS, or webhook provider must also receive the event ID as its provider idempotency key to close the crash window between an external side effect and the inbox commit.

Outbox and inbox tables require operational retention policies. Rows in `FAILED` remain visible for investigation or manual replay rather than being deleted. The publisher currently holds a short database row lock while waiting for Kafka acknowledgement; higher-throughput deployments may replace that implementation with lease-based claiming while preserving the same state model.
