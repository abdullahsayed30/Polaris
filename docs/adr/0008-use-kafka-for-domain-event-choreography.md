# 0008 - Use Kafka for Domain Event Choreography

Date: 2026-05-11

## Status

Accepted

## Context

Not every service interaction requires an immediate response. Order creation, inventory adjustments, notification processing, and failed notification handling are domain facts that other services can react to asynchronously.

## Decision

Polaris will use Kafka for asynchronous domain events and choreography. Current topics include:

| Topic | Producer | Consumers |
| --- | --- | --- |
| `polaris.orders.created` | `order-service` | `inventory-service`, `notification-service` |
| `polaris.inventory.adjusted` | `inventory-service` | `notification-service` |
| `polaris.notifications.dlq` | `notification-service` | Operational consumers |

Event payloads are Java records in `shared` for the current monorepo phase. They must be treated as service contracts and evolved deliberately.

## Consequences

Services can react to durable business events without tight synchronous coupling. The tradeoff is eventual consistency, event compatibility work, and the need for operational handling around retries, ordering, and poison messages.
