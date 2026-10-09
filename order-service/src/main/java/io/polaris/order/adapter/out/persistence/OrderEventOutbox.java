package io.polaris.order.adapter.out.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.polaris.order.adapter.out.observability.DurableTelemetry;
import io.polaris.order.application.port.out.OrderEventRecorder;
import io.polaris.shared.events.OrderCreatedEvent;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class OrderEventOutbox implements OrderEventRecorder {
    private static final String EVENT_TYPE = "OrderCreated";

    private final OutboxEventRepository outboxEvents;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final DurableTelemetry telemetry;

    public OrderEventOutbox(
            OutboxEventRepository outboxEvents,
            ObjectMapper objectMapper,
            @Value("${polaris.kafka.topics.order-created}") String topic, DurableTelemetry telemetry) {
        this.telemetry = telemetry;
        this.outboxEvents = outboxEvents;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    public void enqueue(OrderCreatedEvent event) {
        try (var session = telemetry.child("outbox.event.create", event.metadata().eventId().toString())) {
            OutboxEvent row = OutboxEvent.pending(
                    event.metadata().eventId(),
                    event.orderId().toString(),
                    EVENT_TYPE,
                    event.metadata().version(),
                    topic,
                    event.orderId().toString(),
                    objectMapper.writeValueAsString(event),
                    event.metadata().occurredAt());
            row.setTraceContext(telemetry.capture());
            telemetry.databaseWrite("INSERT", "outbox_events", () -> outboxEvents.saveAndFlush(row));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize order event for the outbox", ex);
        }
    }
}
