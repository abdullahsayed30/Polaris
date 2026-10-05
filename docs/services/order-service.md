# Order Service

`order-service` owns the public order API and the order lifecycle. It persists orders in its own PostgreSQL database, calls the inventory service over gRPC, and delivers order events through a transactional outbox.

## Responsibilities

- Expose the order REST API.
- Persist orders and order items in `polaris_orders`.
- Validate order input and return Problem Details for known API errors.
- Call inventory `ReserveStock` over gRPC as the atomic stock decision.
- Confirm or cancel orders based on the reservation outcome.
- Publish `OrderCreatedEvent` to `polaris.orders.created`.

## HTTP API

The service exposes these endpoints under `/api/v1/orders`. Public traffic should reach them through the gateway.

| Method | Path | Behavior |
| --- | --- | --- |
| `POST` | `/api/v1/orders` | Places an order and returns `201 Created` with the order representation |
| `GET` | `/api/v1/orders/{id}` | Returns an existing order |

`POST /api/v1/orders` accepts at least one item. Each item requires a non-blank SKU, a positive quantity, and a unit price of at least `0.01`. The authenticated JWT subject supplies the customer ID.

Clients may send an `Idempotency-Key` of at most 128 characters. Keys are scoped to the authenticated customer. Repeating the same key and payload returns the original order with `Idempotency-Replayed: true`; reusing the key for a different payload returns `409 Conflict`.

Known API errors:

| Condition | Status | Shape |
| --- | --- | --- |
| Unknown order ID | `404 Not Found` | Spring Problem Details with title `Order not found` |
| Inventory gRPC failure | `503 Service Unavailable` | Spring Problem Details with title `Inventory unavailable` |
| Idempotency key reused with different payload | `409 Conflict` | Problem Details with title `Idempotency conflict` |
| Invalid request payload | `400 Bad Request` | Spring validation error response |

## Order Flow

1. Commit the `PENDING` order, original items, and optional idempotency binding before any remote stock mutation.
2. In a separate local transaction, lock the order and call inventory `ReserveStock` using its persisted ID and items. A successful reservation confirms the order; a rejected reservation cancels it.
3. Commit the final status, completed request binding, and `OrderCreatedEvent` outbox row together. Failures leave the durable intent pending.
4. A scheduled recovery use case retries due pending orders, including headerless requests, without needing a client retry. Failed attempts defer their next retry so other orders can progress.
5. The outbox publisher retries until Kafka acknowledges the stored payload or the configured attempt limit makes the row visibly `FAILED`.

Skipping a preliminary stock check avoids a time-of-check/time-of-use race. If inventory committed but its response was lost, both HTTP retries with the same key and background recovery receive the stored reservation decision instead of decrementing stock again. A failed first attempt still binds the key to its original payload. Headerless HTTP retries are new orders: background recovery does not replace client idempotency.

The resolver holds a local order row lock across the bounded RPC to serialize finalization and prevent duplicate outbox rows. This is a compact, low-throughput tradeoff; a higher-throughput design would need an explicitly designed claim/lease protocol. Recovery defaults to a batch of 50, a 30-second retry delay, a 5-second poll interval, and a 30-second initial delay under `polaris.reservation.recovery`. See [ADR 0020](../adr/0020-recover-pending-orders-durably.md).

The event name is intentionally `OrderCreatedEvent` even when the status is `CANCELLED`; consumers must read the event status.

## Data Ownership

`order-service` owns the `orders`, `order_items`, `order_requests`, and `outbox_events` tables. `order_requests` serializes concurrent uses of a customer-scoped key and binds the key to its original request hash and pending/completed order. `outbox_events` records delivery status, attempts, retry time, publication time, and the last error. Migrations are SQL-based Liquibase changes under `order-service/src/main/resources/db/changelog`.

JPA validates the schema at startup with `hibernate.ddl-auto=validate`. The service does not read or write inventory tables.

## Outbound Dependencies

| Dependency | Use |
| --- | --- |
| PostgreSQL | Order persistence |
| Inventory gRPC | Atomic reservation |
| Kafka | Publish order lifecycle events |
| `shared` | `OrderCreatedEvent` payload |
| `proto-contracts` | Generated inventory gRPC stubs |

The inventory gRPC target is configured with `polaris.inventory.grpc.host`, `polaris.inventory.grpc.port`, and `polaris.inventory.grpc.deadline`. The default deadline is `2s`.

The generated inventory blocking stub is created by the Spring Boot-compatible gRPC client starter. `GrpcInventoryClient` still applies the configured per-call deadline before invoking `CheckStock` and `ReserveStock`.

`order-service` accepts or creates `X-Request-Id` for direct HTTP calls, stores it in MDC as `request.id`, and propagates it to inventory as gRPC `x-request-id` metadata.

## Package Shape

See [ADR 0021](../adr/0021-adopt-hombergs-hexagonal-service-structure.md) and the [service standard](../service-architecture-standard.md) for dependency rules.

| Package | Purpose |
| --- | --- |
| `application.domain.model` | Plain orders, items, statuses and idempotency request state |
| `application.domain.service` | Placement/recovery use cases, business transactions and event mapping |
| `application.port.in` / `.out` | Place/get/recover interfaces; storage, inventory, event and telemetry capabilities |
| `adapter.in.web` / `.scheduling` | REST DTOs, security identity mapping, request correlation and recovery triggers |
| `adapter.out.persistence` | Separate JPA models/mappers, locked storage adapters and transactional event recording |
| `adapter.out.grpc` / `.messaging` / `.observability` | Inventory RPC, outbox delivery and metrics/correlation adapters |
| Service root | Spring wiring, security configuration and typed properties |

## Tests

The integration test starts PostgreSQL and Kafka with Testcontainers and uses a fake gRPC inventory server. It verifies confirmed and cancelled orders, duplicate and concurrent idempotent requests, payload conflicts, recovery after a lost reservation response, order lookup, validation, and Kafka publication. A database mapping test also verifies explicit persistence, stable item IDs, exact decimal scale, timestamps and optimistic versions. Focused unit tests cover gRPC request ID metadata propagation and MDC cleanup.
