# Order Service

`order-service` owns the public order API and the order lifecycle. It persists orders in its own PostgreSQL database, calls the inventory service over gRPC, and publishes order events to Kafka after the local transaction commits.

## Responsibilities

- Expose the order REST API.
- Persist orders and order items in `polaris_orders`.
- Validate order input and return Problem Details for known API errors.
- Call inventory `CheckStock` and `ReserveStock` over gRPC.
- Confirm or cancel orders based on inventory availability and reservation outcome.
- Publish `OrderCreatedEvent` to `polaris.orders.created`.

## HTTP API

The service exposes these endpoints under `/api/v1/orders`. Public traffic should reach them through the gateway.

| Method | Path | Behavior |
| --- | --- | --- |
| `POST` | `/api/v1/orders` | Places an order and returns `201 Created` with the order representation |
| `GET` | `/api/v1/orders/{id}` | Returns an existing order |

`POST /api/v1/orders` accepts a `customerId` and at least one item. Each item requires a non-blank SKU, a positive quantity, and a unit price of at least `0.01`.

Known API errors:

| Condition | Status | Shape |
| --- | --- | --- |
| Unknown order ID | `404 Not Found` | Spring Problem Details with title `Order not found` |
| Inventory gRPC failure | `503 Service Unavailable` | Spring Problem Details with title `Inventory unavailable` |
| Invalid request payload | `400 Bad Request` | Spring validation error response |

## Order Flow

1. The service creates an order in `PENDING` status.
2. The order and items are persisted in the order database.
3. The service calls inventory `CheckStock`.
4. If stock is unavailable, the order is cancelled.
5. If stock is available, the service calls inventory `ReserveStock`.
6. A successful reservation confirms the order; a failed reservation cancels it.
7. `OrderCreatedEvent` is published to Kafka after the database transaction commits.

The event name is intentionally `OrderCreatedEvent` even when the status is `CANCELLED`; consumers must read the event status.

## Data Ownership

`order-service` owns the `orders` and `order_items` tables. Migrations are SQL-based Liquibase changes under `order-service/src/main/resources/db/changelog`.

JPA validates the schema at startup with `hibernate.ddl-auto=validate`. The service does not read or write inventory tables.

## Outbound Dependencies

| Dependency | Use |
| --- | --- |
| PostgreSQL | Order persistence |
| Inventory gRPC | Stock check and reservation |
| Kafka | Publish order lifecycle events |
| `shared` | `OrderCreatedEvent` payload |
| `proto-contracts` | Generated inventory gRPC stubs |

The inventory gRPC target is configured with `polaris.inventory.grpc.host`, `polaris.inventory.grpc.port`, and `polaris.inventory.grpc.deadline`. The default deadline is `2s`.

## Package Shape

| Package | Purpose |
| --- | --- |
| `api` | REST controller, request/response records, API exception handling |
| `application` | Place-order use case, event mapping, transaction boundary |
| `domain` | `Order`, `OrderItem`, and `OrderStatus` |
| `inventory` | Inventory client port and gRPC adapter |
| `messaging` | Kafka topic wiring and after-commit event publisher |
| `persistence` | Spring Data order repository |
| `config` | Kafka and gRPC configuration |

## Tests

The integration test starts PostgreSQL and Kafka with Testcontainers and uses a fake gRPC inventory server. It verifies confirmed orders, cancelled orders, failed reservations, order lookup, validation, and Kafka publication.
