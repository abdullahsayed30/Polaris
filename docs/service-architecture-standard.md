# Service Architecture Standard

Polaris services use a lightweight ports-and-adapters architecture. This keeps the codebase close to Spring Boot conventions while still making domain behavior, inbound APIs, and outbound infrastructure dependencies easy to reason about.

## Package Standard

Every service should follow this package shape unless there is a clear reason to deviate:

| Package | Purpose |
| --- | --- |
| `api` | Inbound REST controllers, request/response DTOs, and API exception handling |
| `application` | Use cases, transaction boundaries, orchestration, and application events |
| `domain` | Entities, enums, value objects, and domain behavior |
| `persistence` | Spring Data repositories and persistence-specific adapters |
| `messaging` | Kafka producers, consumers, topic configuration, and event adapters |
| `inventory`, `payment`, or other integration package | Outbound client ports and adapters for external/internal systems |
| `config` | Spring configuration and typed configuration properties |

## Order Service Shape

`order-service` currently applies the standard as follows:

- `api`: exposes `POST /api/v1/orders` and `GET /api/v1/orders/{id}`.
- `application`: owns the place-order use case and transaction boundary.
- `domain`: owns `Order`, `OrderItem`, and `OrderStatus`.
- `persistence`: owns `OrderRepository`.
- `inventory`: defines the `InventoryClient` port and the gRPC adapter.
- `messaging`: publishes `OrderCreatedEvent` to Kafka after transaction commit.
- `config`: wires Kafka topic creation and the Inventory gRPC channel.

## Why This Architecture

- It keeps Spring framework code at the edges instead of spreading infrastructure concerns through domain logic.
- It gives every service the same mental model, which matters more as the system grows.
- It keeps testing practical: use cases can be tested through APIs, while outbound systems can be replaced by fakes in integration tests.
- It avoids overbuilding full clean architecture for a portfolio blueprint while still showing disciplined service boundaries.

## Rules

- Controllers should not contain business logic beyond request mapping and DTO conversion.
- Application services own transactions and orchestration.
- Domain classes should not depend on Spring, Kafka, gRPC, HTTP, or database clients.
- Outbound integrations should be hidden behind small ports/interfaces when the service logic depends on them.
- Kafka publication that represents a committed state change should run after transaction commit.
- Liquibase changes should be small, ordered, SQL-based, and separated by table or schema concern.
