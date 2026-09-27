package io.polaris.inventory.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.polaris.inventory.application.InventoryEventRecorder;
import io.polaris.inventory.persistence.OutboxEvent;
import io.polaris.inventory.persistence.OutboxEventRepository;
import io.polaris.shared.events.InventoryAdjustedEvent;

@Component
public class InventoryEventOutbox implements InventoryEventRecorder {
    private static final String EVENT_TYPE = "InventoryAdjusted";

    private final OutboxEventRepository outboxEvents;
    private final ObjectMapper objectMapper;
    private final String topic;

    public InventoryEventOutbox(
            OutboxEventRepository outboxEvents,
            ObjectMapper objectMapper,
            @Value("${polaris.kafka.topics.inventory-adjusted}") String topic) {
        this.outboxEvents = outboxEvents;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    public void enqueue(InventoryAdjustedEvent event) {
        try {
            outboxEvents.save(OutboxEvent.pending(
                    event.metadata().eventId(),
                    event.orderId().toString(),
                    EVENT_TYPE,
                    event.metadata().version(),
                    topic,
                    event.orderId().toString(),
                    objectMapper.writeValueAsString(event),
                    event.metadata().occurredAt()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize inventory event for the outbox", ex);
        }
    }
}
