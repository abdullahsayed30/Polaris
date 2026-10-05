# Inventory Service

`inventory-service` owns stock state. It exposes the internal inventory gRPC API, persists inventory items in its own PostgreSQL database, observes order events, and delivers inventory adjustment events through a transactional outbox.

## Responsibilities

- Serve the inventory gRPC contract from `proto-contracts`.
- Check stock availability for requested order lines.
- Reserve stock atomically in the inventory database.
- Publish `InventoryAdjustedEvent` to `polaris.inventory.adjusted`.
- Observe order-created events for future choreography hooks.

## gRPC API

The service implements `polaris.inventory.v1.InventoryService`.

The server is managed by the Spring Boot-compatible gRPC starter. The `InventoryGrpcController` is registered with `@GrpcService`; application code does not start Netty directly.

| RPC | Request | Response | Behavior |
| --- | --- | --- | --- |
| `CheckStock` | `StockRequest` | `StockResponse` | Reports availability per SKU without mutating stock |
| `ReserveStock` | `ReserveRequest` | `ReserveResponse` | Atomically records one reservation decision per order ID |
| `ReleaseStock` | `ReleaseRequest` | `ReleaseResponse` | Idempotently releases a successful reservation |

Invalid request data returns gRPC `INVALID_ARGUMENT`. Reservation precondition failures can return `FAILED_PRECONDITION`.

The service exposes gRPC health checks. Reflection is disabled by default and enabled for `local` and `docker` profiles.

## Reservation Behavior

`ReserveStock` first inserts or locks the reservation record keyed by `order_id`. The unique primary key serializes concurrent duplicate requests. The first request locks inventory rows in SKU order and records either `RESERVED` or `REJECTED`; both decisions are durable. An exact retry returns the stored line-level result. The same order ID with a different normalized SKU/quantity set returns `FAILED_PRECONDITION`, and a released reservation cannot be reserved again.

If any item is missing or insufficient, no inventory row is mutated and the durable response reports `INSUFFICIENT_STOCK`. When every item can be reserved, available quantities are decremented in the same transaction as the reservation decision and inventory outbox record. Event item quantities are negative because they represent stock leaving availability.

`ReleaseStock` transitions `RESERVED` to terminal `RELEASED`, restores all reserved quantities, and records the returned availability snapshot. Repeating the release returns that stored result without restoring stock twice. Releasing a missing or rejected reservation is an invalid transition. Release adjustments are positive.

## Data Ownership

`inventory-service` owns `inventory_items`, `inventory_reservations`, `inventory_reservation_lines`, and `outbox_events` in `polaris_inventory`. Database checks enforce valid states and non-negative quantities, while unique constraints enforce one reservation per order and one line per order/SKU. The outbox row is committed atomically with a successful reservation or release and exposes delivery status, attempts, retry time, and the last error. Liquibase SQL migrations live under `inventory-service/src/main/resources/db/changelog`.

No other service reads or writes this database.

## Kafka

| Topic | Direction | Purpose |
| --- | --- | --- |
| `polaris.orders.created` | Consumed | Observe order lifecycle events |
| `polaris.inventory.adjusted` | Produced | Publish committed stock adjustments |

The current order-event listener logs observed order events. It leaves room for future inventory workflows without making order-service depend on inventory database reads.

## Runtime Configuration

| Property | Default | Purpose |
| --- | --- | --- |
| `server.port` | `8082` | HTTP actuator port |
| `polaris.inventory.grpc.port` | `9090` | gRPC server port |
| `grpc.server.health-service-enabled` | `true` | Enables the starter-managed gRPC health service |
| `grpc.server.reflection-service-enabled` | `false` | Enables gRPC reflection; local and Docker profiles override it to `true` |
| `POLARIS_TRACING_EXPORT_ENABLED` | `false` | Enables OTLP trace export |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | `http://localhost:4318/v1/traces` | OTLP HTTP trace endpoint |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5433/polaris_inventory` | Inventory database |
| `spring.kafka.bootstrap-servers` | `localhost:9092` | Kafka broker |

The `docker` profile switches PostgreSQL and Kafka addresses to Docker service names, enables gRPC reflection, and Docker Compose enables trace export to Tempo.

## Package Shape

See [ADR 0021](../adr/0021-adopt-hombergs-hexagonal-service-structure.md) and the [service standard](../service-architecture-standard.md) for dependency rules.

| Package | Purpose |
| --- | --- |
| `application.domain.model` | Plain stock items, reservations, lines and reservation statuses |
| `application.domain.service` | Stock check, reserve/release orchestration and business transactions |
| `application.port.in` / `.out` | Check/reserve/release use cases; stock, reservation, event and correlation capabilities |
| `adapter.in.grpc` / `.messaging` | gRPC mapping/health/interceptors and existing order-event observation |
| `adapter.out.persistence` | Separate JPA models/mappers, deterministic locking, explicit writes and event recording |
| `adapter.out.messaging` / `.observability` | Outbox delivery and event correlation |
| Service root | Spring wiring and typed properties |

## Tests

The integration test starts PostgreSQL and Kafka with Testcontainers and covers successful and rejected decisions, exact retries after a lost response, concurrent duplicates, conflicting payloads, idempotent release, invalid transitions, database mutations, Kafka publication, and gRPC health/reflection.

Mapping-focused database tests additionally exercise different orders competing for stock, concurrent release retries with stable line IDs, rollback of all mapped state after an outbox failure, and rejection of stale model versions.
