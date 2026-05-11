package io.polaris.inventory.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.polaris.inventory.application.InventoryAdjustedApplicationEvent;
import io.polaris.shared.events.InventoryAdjustedEvent;

@Component
public class InventoryAdjustedKafkaPublisher {
    private final KafkaTemplate<String, InventoryAdjustedEvent> kafkaTemplate;
    private final String topic;

    public InventoryAdjustedKafkaPublisher(
            KafkaTemplate<String, InventoryAdjustedEvent> kafkaTemplate,
            @Value("${polaris.kafka.topics.inventory-adjusted}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(InventoryAdjustedApplicationEvent event) {
        kafkaTemplate.send(topic, event.event().orderId().toString(), event.event());
    }
}
