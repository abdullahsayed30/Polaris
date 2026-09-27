# Shared Module

`shared` is a supporting Maven module for Java types that are intentionally reused across Polaris services. It is not a runtime service and must not become a shortcut around service boundaries.

## Responsibilities

- Provide shared Java event payload records for Kafka producers and consumers.
- Keep cross-service event shapes in one place while Polaris remains a monorepo.
- Avoid duplicating simple event DTOs across services during the blueprint stage.

## Current Event Types

| Type | Producer | Consumers | Topic |
| --- | --- | --- | --- |
| `OrderCreatedEvent` | `order-service` | `inventory-service`, `notification-service` | `polaris.orders.created` |
| `InventoryAdjustedEvent` | `inventory-service` | `notification-service` | `polaris.inventory.adjusted` |

## OrderCreatedEvent

`OrderCreatedEvent` represents the committed result of an order placement attempt.

| Field | Meaning |
| --- | --- |
| `orderId` | Order identifier from `order-service` |
| `customerId` | Customer identifier derived from the authenticated JWT subject |
| `status` | Final placement outcome at publication time: `CONFIRMED` or `CANCELLED` |
| `items` | Ordered items with item ID, SKU, quantity, and unit price |
| `createdAt` | Order creation timestamp |
| `metadata` | Immutable event identity, version, occurrence time, and correlation/causation |

The event name is about the order aggregate being created. Consumers must inspect `status`; a created order can be confirmed or cancelled.

## InventoryAdjustedEvent

`InventoryAdjustedEvent` represents a committed inventory quantity change caused by a reservation.

| Field | Meaning |
| --- | --- |
| `orderId` | Order that caused the adjustment |
| `items` | Adjusted SKUs with quantity change and remaining available quantity |
| `adjustedAt` | Adjustment timestamp |
| `metadata` | Immutable event identity, version, occurrence time, and correlation/causation |

Reservation adjustments use negative `quantityChanged` values because stock is leaving availability. Release adjustments use positive values.

## Metadata and Legacy Records

Current producers emit `EventMetadata`: UUID `eventId`, integer `version`, `occurredAt`, `correlationId`, and optional `causationId`. Outbox retries preserve the entire serialized payload and identity. Notification uses the identity to suppress duplicate delivery with its service-owned inbox.

Kafka may retain records produced before metadata existed. Notification accepts those only when the metadata member is absent, deriving an identity from topic/partition/offset and occurrence time from `createdAt` or `adjustedAt`. Explicit null or malformed metadata is a poison event, not a legacy event. Republish to a different offset creates a different legacy identity; use explicit event IDs when migrating/replaying outside the original source coordinates. See [contracts](../contracts/README.md) for rollout rules.

## Boundary Rules

- `shared` may contain stable event payloads and small cross-service value types.
- `shared` must not contain service application logic, persistence entities, repositories, HTTP clients, gRPC clients, Kafka listeners, or Spring configuration.
- `shared` must not replace `proto-contracts`; protobuf and gRPC contracts belong in `proto-contracts`.
- Event payload changes must be treated as contract changes because multiple services compile against and serialize these records.
- Build quality gates enforce this boundary: services may depend on `shared` and generated `proto-contracts` types, but must not depend on another service module.

## Versioning

While Polaris remains a monorepo, `shared` inherits the parent version and is released with the rest of the reactor. If services split into separate repositories later, shared event contracts should either move to a dedicated versioned event-contract artifact or be replaced by schema-managed event definitions.
