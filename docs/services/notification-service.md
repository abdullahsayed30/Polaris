# Notification Service

`notification-service` is a Kafka consumer workflow. It reacts to order and inventory events, runs notification handling with retry, and publishes failed events to a dead-letter topic.

## Responsibilities

- Consume order-created events.
- Consume inventory-adjusted events.
- Execute notification handling through a small application port.
- Retry transient handler failures with Resilience4j.
- Publish failed records to `polaris.notifications.dlq` after retry exhaustion.

## Runtime Model

The service runs with `spring.main.web-application-type=none` by default and does not expose a business HTTP API. The `docker` profile switches on an actuator-only HTTP listener on port `8083` so Docker Compose can healthcheck the consumer and Prometheus can scrape metrics.

The service does not own a database in the current blueprint stage. The local Compose stack still provisions a dedicated notification PostgreSQL container to preserve the database-per-service runtime boundary for later notification persistence.

The current notification handler logs confirmation and inventory adjustment messages. This keeps the workflow testable while leaving real email, SMS, webhook, or provider integrations behind the `NotificationHandler` port.

## Kafka

| Topic | Direction | Payload |
| --- | --- | --- |
| `polaris.orders.created` | Consumed | `OrderCreatedEvent` |
| `polaris.inventory.adjusted` | Consumed | `InventoryAdjustedEvent` |
| `polaris.notifications.dlq` | Produced | `NotificationDeadLetterEvent` |

The consumer group is `notification-service` by default.

## Retry and Dead Lettering

Notification handling is wrapped in a Resilience4j retry named `notification-workflow`.

| Property | Default |
| --- | --- |
| `polaris.notifications.retry.max-attempts` | `3` |
| `polaris.notifications.retry.initial-interval` | `250ms` |
| `polaris.notifications.retry.multiplier` | `2.0` |

When retries are exhausted, the service publishes a dead-letter event containing the source topic, source key, event type, original payload, error type, error message, and failure timestamp.

## Package Shape

| Package | Purpose |
| --- | --- |
| `application` | Notification handling port and logging implementation |
| `config` | Retry configuration and typed retry properties |
| `messaging` | Kafka listeners, dead-letter event, dead-letter publisher, topic wiring |

## Tests

The integration test starts Kafka with Testcontainers, produces order and inventory events, verifies handler execution, simulates a notification outage, and asserts that a dead-letter event is published after configured retry exhaustion.
