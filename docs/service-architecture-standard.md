# Service Architecture Standard

Polaris services use a lightweight ports-and-adapters architecture. This keeps the codebase close to Spring Boot conventions while still making domain behavior, inbound APIs, and outbound infrastructure dependencies easy to reason about.

## Package Standard

Every service should follow this package shape unless there is a clear reason to deviate:

| Package | Purpose |
| --- | --- |
| `api` | Inbound REST/gRPC adapters, request/response DTOs, and API exception handling |
| `application` | Use cases, transaction boundaries, orchestration, and outbound event/delivery ports |
| `domain` | Entities, enums, value objects, and domain behavior |
| `persistence` | Spring Data repositories, persistence adapters, and outbox/inbox bookkeeping entities |
| `messaging` | Kafka producers, consumers, topic configuration, and event adapters |
| `inventory`, `payment`, or other integration package | Outbound client ports and adapters for external/internal systems |
| `config` | Spring configuration and typed configuration properties |

## Order Service Shape

`order-service` currently applies the standard as follows:

- `api`: exposes `POST /api/v1/orders` and `GET /api/v1/orders/{id}`.
- `application`: owns order placement, durable pending-order recovery, transaction boundaries, and the event-recording port.
- `domain`: owns orders, items, statuses, and idempotency request identity.
- `persistence`: owns order/request repositories and outbox storage.
- `inventory`: defines the `InventoryClient` port and the gRPC adapter.
- `messaging`: implements the event-recording port and publishes committed outbox rows to Kafka.
- `config`: wires Kafka topic creation and the Inventory gRPC channel.

## Inventory Service Shape

`inventory-service` currently applies the standard as follows:

- `api`: exposes the Inventory gRPC controller.
- `application`: owns stock checks, reservations/releases, transaction boundaries, and the event-recording port.
- `domain`: owns inventory items, reservations, lines, and reservation statuses.
- `persistence`: owns inventory/reservation repositories and outbox storage.
- `messaging`: observes order events and publishes `InventoryAdjustedEvent` after reservation commit.
- `config`: owns gRPC interceptors, starter configuration, and topic configuration.

## Notification Service Shape

- `messaging`: decodes Kafka records, maps them to plain application delivery data, publishes dead letters through an application port, and manages acknowledgement/error handling.
- `application`: owns the inbox transaction, duplicate suppression, handler retries, and terminal processing outcome. The current handler logs simulated notifications.
- `persistence`: owns inbox bookkeeping entities, statuses, and Spring Data repositories; no Kafka transport types enter entities.
- `config`: owns typed retry properties and retry wiring.

## Gateway Shape

`gateway` is an edge service rather than a domain service, so it keeps infrastructure concerns explicit:

- `config`: owns WebFlux security, CORS configuration, and typed rate-limit properties.
- `logging`: owns request logging and request ID response propagation.
- `ratelimit`: owns global rate limiting, key resolution, and Redis/in-memory limiter implementations.
- `application.yml`: owns Spring Cloud Gateway route definitions for the public order API.

## Why This Architecture

- It keeps Spring framework code at the edges instead of spreading infrastructure concerns through domain logic.
- It gives every service the same mental model, which matters more as the system grows.
- It keeps testing practical: use cases can be tested through APIs, while outbound systems can be replaced by fakes in integration tests.
- It avoids overbuilding full clean architecture for a portfolio blueprint while still showing disciplined service boundaries.

## Rules

- Controllers should not contain business logic beyond request mapping and DTO conversion.
- Application services own transactions and orchestration.
- Domain classes should not depend on Spring, Kafka, gRPC, HTTP, or database clients. Existing JPA entity mappings are allowed: this is lightweight ports-and-adapters, not a mandatory persistence-free domain rewrite.
- Outbound integrations should be hidden behind small ports/interfaces when the service logic depends on them.
- Application code must not import concrete messaging adapters or transport record types. Kafka listeners must delegate business transactions and workflow to application services.
- Kafka publication that represents a committed state change runs after commit. Under ADR 0019, application-owned ports record durable outbox rows inside the business transaction; publishers deliver them afterward. Publisher delivery-bookkeeping transactions are infrastructure concerns, not business use cases.
- All Spring Data repositories and outbox/inbox storage models belong in `persistence`; typed configuration properties are records in `config`.
- Liquibase changes should be small, ordered, SQL-based, and separated by table or schema concern.
- Service modules may depend on `shared` and `proto-contracts`, but must not depend on another runtime service module.
- Reusable Java contracts and helpers belong under `shared/src/main/java/io/polaris/shared`.

`contract-tests` is a test-only harness, not a runtime service dependency exception. `ServiceArchitectureTest` checks the source package boundaries during `test`, and Maven Enforcer checks module boundaries. These checks supplement review rather than proving all architectural intent. Agents must also follow the root [AGENTS.md](../AGENTS.md) and accepted ADRs.
