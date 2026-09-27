# 0009 - Publish Domain Events After Transaction Commit

Date: 2026-05-11

## Status

Superseded by [0019](0019-use-transactional-outbox-and-consumer-inbox.md)

## Context

Kafka events represent committed business facts. If a service publishes an event before its database transaction commits, downstream services may observe state that later rolls back.

## Decision

Polaris originally published state-change events with Spring transaction event listeners using `TransactionPhase.AFTER_COMMIT`. ADR 0019 replaces this mechanism with a transactional outbox.

## Consequences

This decision documented the original blueprint stage. The acknowledged database-commit/Kafka-send gap is now closed by the transactional outbox in ADR 0019.
