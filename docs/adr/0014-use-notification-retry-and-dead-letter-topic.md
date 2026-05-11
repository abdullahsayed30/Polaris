# 0014 - Use Notification Retry and Dead-Letter Topic

Date: 2026-05-11

## Status

Accepted

## Context

Notification workflows are downstream side effects. They should tolerate transient failures without blocking the order or inventory services, but failed events must not disappear silently.

## Decision

`notification-service` consumes `polaris.orders.created` and `polaris.inventory.adjusted`. Handler execution is wrapped in a Resilience4j retry with configurable attempts, initial interval, and multiplier. If handling still fails after retry exhaustion, the service publishes a `NotificationDeadLetterEvent` to `polaris.notifications.dlq`.

The current handler logs notification outcomes as a stand-in for real email, SMS, or webhook providers.

## Consequences

Transient notification failures get retried, and unrecoverable failures become visible through a durable dead-letter topic. The tradeoff is that operational tooling is needed to inspect, replay, or archive dead-letter records in a production environment.
