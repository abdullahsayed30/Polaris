package io.polaris.order.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.polaris.order.application.OrderCreatedApplicationEvent;
import io.polaris.shared.events.OrderCreatedEvent;

@Component
public class OrderCreatedKafkaPublisher {
    private final KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;
    private final String topic;

    public OrderCreatedKafkaPublisher(
            KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate,
            @Value("${polaris.kafka.topics.order-created}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(OrderCreatedApplicationEvent applicationEvent) {
        OrderCreatedEvent event = applicationEvent.event();
        kafkaTemplate.send(topic, event.orderId().toString(), event);
    }
}
