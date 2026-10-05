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

The service owns the `polaris_notifications` database. Its `inbox_events` table records the source topic coordinates and terminal processing state for every valid event ID. Redelivery of a committed event ID is skipped.

The current notification handler logs simulated confirmation and inventory adjustment messages; it does not send email. This keeps the workflow testable while leaving real email, SMS, webhook, or provider integrations behind the `NotificationHandler` port.

## Kafka

| Topic | Direction | Payload |
| --- | --- | --- |
| `polaris.orders.created` | Consumed | `OrderCreatedEvent` |
| `polaris.inventory.adjusted` | Consumed | `InventoryAdjustedEvent` |
| `polaris.notifications.dlq` | Produced | `NotificationDeadLetterEvent` |

The consumer group is `notification-service` by default.

Both source contracts carry `EventMetadata` with a stable event ID and version. The inbox uses the event ID as its primary key. Real notification providers should receive the same ID as their idempotency key because a database inbox alone cannot make an external side effect and a local commit atomic.

Retained older events without the metadata member remain supported: the Kafka adapter assigns a deterministic identity from topic/partition/offset and preserves the original occurrence timestamp. An explicit null or invalid metadata member is rejected. Replaying a legacy record at a different offset creates a new identity. Deploy compatible consumers before new producers; see [contract compatibility](../../contracts/README.md).

## Retry and Dead Lettering

Notification handling is wrapped in a Resilience4j retry named `notification-workflow`.

| Property | Default |
| --- | --- |
| `polaris.notifications.retry.max-attempts` | `3` |
| `polaris.notifications.retry.initial-interval` | `250ms` |
| `polaris.notifications.retry.multiplier` | `2.0` |

When retries are exhausted, the service publishes a dead-letter event containing its own stable metadata plus the source event ID/version, topic, partition, offset, key, original payload, error, and failure timestamp. Malformed JSON follows the same poison-event path without a source event ID.

The listener waits for Kafka to acknowledge the dead-letter record. If publication fails or times out, the exception escapes and the container retries the source record indefinitely with `polaris.notifications.dlq.redelivery-backoff`; it does not acknowledge and lose the source message. Kafka record acknowledgement is explicit.

## Package Shape

See [ADR 0021](../adr/0021-adopt-hombergs-hexagonal-service-structure.md) and the [service standard](../service-architecture-standard.md) for dependency rules.

| Package | Purpose |
| --- | --- |
| `application.domain.service` | Inbox transaction, duplicate suppression and retry/outcome orchestration |
| `application.port.in` / `.out` | Process/reject and plain delivery input; inbox receipt, retry, handler and DLQ capabilities |
| `adapter.in.messaging` | Kafka decoding, legacy compatibility, acknowledgement and use-case delegation |
| `adapter.out.persistence` | Inbox entity/repository and managed receipt implementation |
| `adapter.out.messaging` / `.retry` / `.logging` | Confirmed dead-letter delivery, Resilience4j and simulated notification handler |
| Service root | Retry/topic/error-handler wiring and typed configuration records |

## Tests

The integration test starts PostgreSQL and Kafka with Testcontainers, produces order and inventory events, verifies inbox-backed duplicate suppression, simulates a notification outage, and asserts that a dead-letter event is published after configured retry exhaustion. Focused unit tests cover transient retry, poison payloads, duplicate delivery, and failed dead-letter broker acknowledgement.
