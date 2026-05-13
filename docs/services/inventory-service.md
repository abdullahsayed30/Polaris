# Inventory Service

`inventory-service` owns stock state. It exposes the internal inventory gRPC API, persists inventory items in its own PostgreSQL database, observes order events, and publishes inventory adjustment events after successful reservations.

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
| `ReserveStock` | `ReserveRequest` | `ReserveResponse` | Reserves stock if every requested SKU has enough available quantity |

Invalid request data returns gRPC `INVALID_ARGUMENT`. Reservation precondition failures can return `FAILED_PRECONDITION`.

The service exposes gRPC health checks. Reflection is disabled by default and enabled for `local` and `docker` profiles.

## Reservation Behavior

`ReserveStock` loads requested inventory rows with a pessimistic write lock. The service first builds a reservation preview. If any item is missing or insufficient, no inventory row is mutated and the response reports `INSUFFICIENT_STOCK`.

When every item can be reserved, the service decrements available quantities and publishes an `InventoryAdjustedEvent` after the transaction commits. Event item quantities are negative because they represent stock leaving availability.

## Data Ownership

`inventory-service` owns the `inventory_items` table in `polaris_inventory`. The table stores SKU, available quantity, timestamps, and an optimistic version column. Liquibase SQL migrations live under `inventory-service/src/main/resources/db/changelog`.

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

| Package | Purpose |
| --- | --- |
| `api` | gRPC adapter |
| `application` | Stock check, reservation, transaction boundary, application events |
| `domain` | `InventoryItem` domain model |
| `messaging` | Kafka listener, topic wiring, after-commit publisher |
| `persistence` | Spring Data inventory repository |
| `config` | gRPC interceptors, observability wiring, and topic configuration |

## Tests

The integration test starts PostgreSQL and Kafka with Testcontainers, calls the starter-managed gRPC server, verifies stock checks, successful reservations, insufficient-stock behavior, gRPC health/reflection, database mutations, and Kafka publication.
