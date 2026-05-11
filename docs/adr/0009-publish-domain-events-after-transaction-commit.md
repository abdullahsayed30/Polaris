# 0009 - Publish Domain Events After Transaction Commit

Date: 2026-05-11

## Status

Accepted

## Context

Kafka events represent committed business facts. If a service publishes an event before its database transaction commits, downstream services may observe state that later rolls back.

## Decision

Polaris publishes state-change events with Spring transaction event listeners using `TransactionPhase.AFTER_COMMIT`. `order-service` publishes `OrderCreatedEvent` only after the order transaction commits. `inventory-service` publishes `InventoryAdjustedEvent` only after the reservation transaction commits.

## Consequences

Downstream consumers receive events that correspond to committed local state. The current implementation does not include a transactional outbox, so there is still a failure window between database commit and Kafka send. That gap is acceptable for the current blueprint stage and should be revisited before a stricter production release.
