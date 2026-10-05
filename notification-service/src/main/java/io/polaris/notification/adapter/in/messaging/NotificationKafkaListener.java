package io.polaris.notification.adapter.in.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.polaris.notification.application.port.in.NotificationDelivery;
import io.polaris.notification.application.port.in.ProcessNotificationUseCase;
import io.polaris.shared.events.EventMetadata;
import io.polaris.shared.events.InventoryAdjustedEvent;
import io.polaris.shared.events.OrderCreatedEvent;

@Component
public class NotificationKafkaListener {
    private final ObjectMapper objectMapper;
    private final ProcessNotificationUseCase notifications;

    public NotificationKafkaListener(ObjectMapper objectMapper, ProcessNotificationUseCase notifications) {
        this.objectMapper = objectMapper;
        this.notifications = notifications;
    }

    @KafkaListener(topics = "${polaris.kafka.topics.orders-created}", groupId = "${spring.kafka.consumer.group-id}")
    public void onOrderCreated(ConsumerRecord<String, String> record) {
        NotificationDelivery delivery = delivery(record, "OrderCreated");
        OrderCreatedEvent event;
        try {
            event = objectMapper.readValue(record.value(), OrderCreatedEvent.class);
            if (isLegacy(record)) {
                event = new OrderCreatedEvent(
                        legacyMetadata(record, event.createdAt(), OrderCreatedEvent.EVENT_VERSION),
                        event.orderId(), event.customerId(), event.status(), event.items(), event.createdAt());
            }
            validateMetadata(event.metadata(), OrderCreatedEvent.EVENT_VERSION);
        } catch (Exception ex) {
            notifications.reject(delivery, ex);
            return;
        }
        notifications.process(delivery, event);
    }

    @KafkaListener(topics = "${polaris.kafka.topics.inventory-adjusted}", groupId = "${spring.kafka.consumer.group-id}")
    public void onInventoryAdjusted(ConsumerRecord<String, String> record) {
        NotificationDelivery delivery = delivery(record, "InventoryAdjusted");
        InventoryAdjustedEvent event;
        try {
            event = objectMapper.readValue(record.value(), InventoryAdjustedEvent.class);
            if (isLegacy(record)) {
                event = new InventoryAdjustedEvent(
                        legacyMetadata(record, event.adjustedAt(), InventoryAdjustedEvent.EVENT_VERSION),
                        event.orderId(), event.items(), event.adjustedAt());
            }
            validateMetadata(event.metadata(), InventoryAdjustedEvent.EVENT_VERSION);
        } catch (Exception ex) {
            notifications.reject(delivery, ex);
            return;
        }
        notifications.process(delivery, event);
    }

    private boolean isLegacy(ConsumerRecord<String, String> record) throws Exception {
        JsonNode payload = objectMapper.readTree(record.value());
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("event must be a JSON object");
        }
        // Only absent metadata identifies the legacy contract. Null or invalid metadata is poison.
        // Read the typed event from the original JSON, never this tree: monetary precision and scale must survive.
        return !payload.has("metadata");
    }

    private EventMetadata legacyMetadata(ConsumerRecord<String, String> record, Instant occurredAt, int version) {
        Objects.requireNonNull(occurredAt, "legacy occurrence time must not be null");
        String identity = "notification-legacy:" + record.topic() + ":" + record.partition() + ":" + record.offset();
        return new EventMetadata(
                UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)),
                version, occurredAt, record.key(), null);
    }

    private void validateMetadata(EventMetadata metadata, int expectedVersion) {
        Objects.requireNonNull(metadata, "event metadata must not be null");
        if (metadata.version() != expectedVersion) {
            throw new IllegalArgumentException("Unsupported event metadata version: " + metadata.version());
        }
    }

    private NotificationDelivery delivery(ConsumerRecord<String, String> record, String eventType) {
        return new NotificationDelivery(record.topic(), record.partition(), record.offset(), record.key(),
                record.value(), eventType);
    }
}
