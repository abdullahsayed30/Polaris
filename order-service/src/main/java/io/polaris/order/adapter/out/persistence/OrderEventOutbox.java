package io.polaris.order.adapter.out.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.polaris.order.application.port.out.OrderEventRecorder;
import io.polaris.shared.events.OrderCreatedEvent;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class OrderEventOutbox implements OrderEventRecorder {
    private static final String EVENT_TYPE = "OrderCreated";

    private final OutboxEventRepository outboxEvents;
    private final ObjectMapper objectMapper;
    private final String topic;

    public OrderEventOutbox(
            OutboxEventRepository outboxEvents,
            ObjectMapper objectMapper,
            @Value("${polaris.kafka.topics.order-created}") String topic) {
        this.outboxEvents = outboxEvents;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    public void enqueue(OrderCreatedEvent event) {
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
            throw new IllegalStateException("Cannot serialize order event for the outbox", ex);
        }
    }
}
