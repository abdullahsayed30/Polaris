# 0010 - Use Reservation Flow Instead of Distributed Transactions

Date: 2026-05-11

## Status

Accepted

## Context

Order placement touches order state and inventory stock. A distributed transaction across service databases would make the workflow look simple on paper, but it would weaken service ownership and introduce coordination complexity that most production microservice systems avoid.

## Decision

Polaris will not use distributed database transactions across services. `order-service` creates an order, requests stock reservation through the inventory gRPC contract, and confirms or cancels the order based on that atomic reservation result. A separate check-before-reserve is not part of order placement because it creates a time-of-check/time-of-use race.

`inventory-service` stores one reservation decision per order ID. Concurrent requests for the same order serialize on that record. An exact duplicate returns the stored `RESERVED` or `REJECTED` decision without mutating inventory again; reuse of the order ID with different items is rejected. `RELEASED` is a terminal state reached through the idempotent `ReleaseStock` compensation RPC. Inventory rows are locked in deterministic SKU order while quantities change.

Clients may supply `Idempotency-Key` when creating an order. Keys are scoped to the authenticated customer and bind to a request fingerprint. The same key and payload returns the original order, while a different payload returns `409 Conflict`. The key also derives a stable order ID, so a retry after a lost reservation response reaches the same inventory reservation.

This is a reservation workflow, not a full saga orchestrator. Placement normally resolves synchronously, while domain events allow other services to react asynchronously. [ADR 0020](0020-recover-pending-orders-durably.md) extends the workflow with a committed pending-order intent before RPC and a background recovery worker. A lost response or failed finalization no longer relies solely on the client retrying; recovery reuses the persisted order identity and original payload.

## Consequences

The system keeps database ownership clear and avoids two-phase commit. Persisted decisions make gRPC retries safe and the release transition provides an explicit compensation mechanism. Reservation expiration and a full saga orchestrator remain possible future additions.
