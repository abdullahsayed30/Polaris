package io.polaris.notification.messaging;

import java.time.Instant;
import java.util.function.Consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.resilience4j.retry.Retry;

import io.polaris.notification.application.NotificationHandler;
import io.polaris.shared.events.InventoryAdjustedEvent;
import io.polaris.shared.events.OrderCreatedEvent;

@Component
public class NotificationKafkaListener {
    private static final Logger log = LoggerFactory.getLogger(NotificationKafkaListener.class);

    private final ObjectMapper objectMapper;
    private final NotificationHandler notificationHandler;
    private final Retry notificationRetry;
    private final NotificationDeadLetterPublisher deadLetterPublisher;

    public NotificationKafkaListener(
            ObjectMapper objectMapper,
            NotificationHandler notificationHandler,
            Retry notificationRetry,
            NotificationDeadLetterPublisher deadLetterPublisher) {
        this.objectMapper = objectMapper;
        this.notificationHandler = notificationHandler;
        this.notificationRetry = notificationRetry;
        this.deadLetterPublisher = deadLetterPublisher;
    }

    @KafkaListener(topics = "${polaris.kafka.topics.orders-created}", groupId = "${spring.kafka.consumer.group-id}")
    public void onOrderCreated(ConsumerRecord<String, String> record) {
        handle(record, "OrderCreated", OrderCreatedEvent.class, notificationHandler::handle);
    }

    @KafkaListener(topics = "${polaris.kafka.topics.inventory-adjusted}", groupId = "${spring.kafka.consumer.group-id}")
    public void onInventoryAdjusted(ConsumerRecord<String, String> record) {
        handle(record, "InventoryAdjusted", InventoryAdjustedEvent.class, notificationHandler::handle);
    }

    private <T> void handle(
            ConsumerRecord<String, String> record,
            String eventType,
            Class<T> eventClass,
            Consumer<T> handler) {
        try {
            T event = objectMapper.readValue(record.value(), eventClass);
            Retry.decorateRunnable(notificationRetry, () -> handler.accept(event)).run();
        } catch (Exception ex) {
            NotificationDeadLetterEvent deadLetter = new NotificationDeadLetterEvent(
                    record.topic(),
                    record.key(),
                    eventType,
                    record.value(),
                    ex.getClass().getName(),
                    ex.getMessage(),
                    Instant.now());
            deadLetterPublisher.publish(deadLetter);
            log.error(
                    "Notification handling failed eventType={} sourceTopic={} sourceKey={} dlqTopic={}",
                    eventType,
                    record.topic(),
                    record.key(),
                    deadLetterPublisher.topic(),
                    ex);
        }
    }
}
