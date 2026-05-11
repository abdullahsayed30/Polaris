# 0010 - Use Reservation Flow Instead of Distributed Transactions

Date: 2026-05-11

## Status

Accepted

## Context

Order placement touches order state and inventory stock. A distributed transaction across service databases would make the workflow look simple on paper, but it would weaken service ownership and introduce coordination complexity that most production microservice systems avoid.

## Decision

Polaris will not use distributed database transactions across services. `order-service` creates an order, checks stock through the inventory gRPC contract, requests stock reservation, and confirms or cancels the order based on the reservation result. `inventory-service` owns the stock mutation in its own transaction and uses pessimistic locking when reserving inventory rows.

This is a reservation workflow, not a full saga orchestrator. The current flow is intentionally compact and synchronous for order placement, while domain events allow other services to react asynchronously.

## Consequences

The system keeps database ownership clear and avoids two-phase commit. The tradeoff is that failure handling must be explicit. Future versions may add idempotency keys, reservation expiration, compensating actions, or an outbox-backed saga if the workflow grows.
