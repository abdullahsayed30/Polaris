# 0020 - Recover Pending Orders Durably

Date: 2026-09-27

## Status

Accepted. Extends [ADR 0010](0010-use-reservation-flow-instead-of-distributed-transactions.md); retains database-per-service ownership and the reservation contract.

## Context

An inventory reservation can commit even when its gRPC response is lost or the order transaction subsequently fails. A stable idempotency key makes client retries safe, but it does not guarantee that the client retries. Previously, rolling back the order transaction could leave reserved stock without a durable local order to recover. Requests without an idempotency header had no stable recovery identity at all.

## Decision

Order placement uses two local transaction boundaries separated by a committed recovery intent:

1. Commit a `PENDING` order with the original items and stable order ID before calling inventory. When supplied, the customer-scoped idempotency key and request fingerprint bind to that order in this same transaction. This also prevents a failed first attempt from allowing the key to be rebound to a different payload.
2. Resolve that order by calling `ReserveStock` with its persisted identity and items. Inventory returns its durable idempotent decision. Commit the final order status, completed request binding, and order outbox event together. An uncertain response or failed finalization leaves the original order pending.
3. A scheduled application recovery use case retries due pending orders using their original identity. Failed attempts defer the next retry in a separate local transaction, allowing other orders to progress. Recovery does not depend on a client retry, including for headerless requests.

The compact implementation locks an order row while resolving it, including across the bounded gRPC call. This serializes competing HTTP/recovery attempts and prevents duplicate finalization or outbox insertion. It is a deliberate low-throughput tradeoff, not a claim that holding locks across RPC scales indefinitely. No cross-service lock or transaction is introduced.

Recovery defaults are `polaris.reservation.recovery.batch-size=50`, `retry-delay=30s`, `poll-interval=5s`, and `initial-delay=30s`. A due-time index supports the pending scan; outcome counters and structured logs expose resolved/retried attempts. Retrying continues while an order remains pending; persistent failures require operator investigation.

## Consequences

Lost responses, process restarts, and local finalization failures retain enough durable state to converge on the inventory decision. Successful reservation is recovered forward to a confirmed order, not blindly released after a timeout. `ReleaseStock` remains an explicit compensation primitive; this decision does not add cancellation, expiration, or a general saga engine.

An HTTP failure can now leave a visible pending order that later completes. Clients must reuse `Idempotency-Key` to retry safely and retrieve the original result: two headerless HTTP attempts intentionally create two distinct orders, even though each can recover independently. Retry scheduling depends on the order service and its database becoming available; there is no fixed completion-time guarantee.

An already-upgraded database must retain compatible constraints and durable pending state on application rollback. Do not remove pending orders or release stock merely because the original HTTP response failed. Testcontainers regressions exercise lost responses, failed event recording, payload conflicts, and concurrent recovery; passing fast mocked tests alone is not sufficient evidence for these transaction guarantees.
