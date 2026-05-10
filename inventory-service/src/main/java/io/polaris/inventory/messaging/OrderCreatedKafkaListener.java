package io.polaris.inventory.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.polaris.shared.events.OrderCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderCreatedKafkaListener {
    private static final Logger log = LoggerFactory.getLogger(OrderCreatedKafkaListener.class);

    private final ObjectMapper objectMapper;

    public OrderCreatedKafkaListener(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${polaris.kafka.topics.orders-created}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onMessage(String payload) throws Exception {
        OrderCreatedEvent event = objectMapper.readValue(payload, OrderCreatedEvent.class);
        log.info(
                "Observed order event orderId={} status={} itemCount={}",
                event.orderId(),
                event.status(),
                event.items().size()
        );
    }
}
