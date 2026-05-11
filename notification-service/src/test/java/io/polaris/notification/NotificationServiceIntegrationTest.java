package io.polaris.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.polaris.notification.application.NotificationHandler;
import io.polaris.notification.messaging.NotificationDeadLetterEvent;
import io.polaris.shared.events.InventoryAdjustedEvent;
import io.polaris.shared.events.OrderCreatedEvent;

@SpringBootTest(properties = {
        "spring.kafka.consumer.group-id=notification-service-it",
        "polaris.notifications.retry.initial-interval=10ms",
        "polaris.notifications.retry.max-attempts=3"
})
@Testcontainers
class NotificationServiceIntegrationTest {
    static final String ORDER_CREATED_TOPIC = "polaris.orders.created";
    static final String INVENTORY_ADJUSTED_TOPIC = "polaris.inventory.adjusted";
    static final String NOTIFICATIONS_DLQ_TOPIC = "polaris.notifications.dlq";

    @Container
    static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    RecordingNotificationHandler notificationHandler;

    @Autowired
    ObjectMapper objectMapper;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @BeforeEach
    void setUp() {
        notificationHandler.reset();
    }

    @Test
    void consumesOrderCreatedAndInventoryAdjustedEvents() throws Exception {
        OrderCreatedEvent orderEvent = orderCreatedEvent();
        InventoryAdjustedEvent inventoryEvent = inventoryAdjustedEvent(orderEvent.orderId());

        kafkaTemplate.send(ORDER_CREATED_TOPIC, orderEvent.orderId().toString(), orderEvent)
                .get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(INVENTORY_ADJUSTED_TOPIC, inventoryEvent.orderId().toString(), inventoryEvent)
                .get(10, TimeUnit.SECONDS);

        assertThat(notificationHandler.awaitOrderAttempts(1)).isTrue();
        assertThat(notificationHandler.awaitInventoryAttempts(1)).isTrue();
        assertThat(notificationHandler.lastOrder().orderId()).isEqualTo(orderEvent.orderId());
        assertThat(notificationHandler.lastInventoryAdjustment().orderId()).isEqualTo(orderEvent.orderId());
    }

    @Test
    void publishesDeadLetterEventAfterRetryExhaustion() throws Exception {
        OrderCreatedEvent orderEvent = orderCreatedEvent();
        notificationHandler.failOrderNotifications();

        kafkaTemplate.send(ORDER_CREATED_TOPIC, orderEvent.orderId().toString(), orderEvent)
                .get(10, TimeUnit.SECONDS);

        assertThat(notificationHandler.awaitOrderAttempts(3)).isTrue();

        NotificationDeadLetterEvent deadLetter = awaitDeadLetterEvent(orderEvent.orderId());
        assertThat(deadLetter.sourceTopic()).isEqualTo(ORDER_CREATED_TOPIC);
        assertThat(deadLetter.sourceKey()).isEqualTo(orderEvent.orderId().toString());
        assertThat(deadLetter.eventType()).isEqualTo("OrderCreated");
        assertThat(deadLetter.payload()).contains(orderEvent.orderId().toString());
        assertThat(deadLetter.errorType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(deadLetter.errorMessage()).isEqualTo("simulated notification outage");
    }

    private NotificationDeadLetterEvent awaitDeadLetterEvent(UUID orderId) throws Exception {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "notification-dlq-it-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        try (Consumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(NOTIFICATIONS_DLQ_TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();

            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
                for (ConsumerRecord<String, String> record : records) {
                    if (record.value().contains(orderId.toString())) {
                        return objectMapper.readValue(record.value(), NotificationDeadLetterEvent.class);
                    }
                }
            }
        }

        return fail("Timed out waiting for dead-letter event for order " + orderId);
    }

    private OrderCreatedEvent orderCreatedEvent() {
        return new OrderCreatedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                OrderCreatedEvent.OrderStatus.CONFIRMED,
                List.of(new OrderCreatedEvent.Item(
                        UUID.randomUUID(),
                        "SKU-COFFEE-001",
                        2,
                        new BigDecimal("19.99"))),
                Instant.now());
    }

    private InventoryAdjustedEvent inventoryAdjustedEvent(UUID orderId) {
        return new InventoryAdjustedEvent(
                orderId,
                List.of(new InventoryAdjustedEvent.Item("SKU-COFFEE-001", -2, 8)),
                Instant.now());
    }

    @TestConfiguration
    static class TestNotificationConfiguration {
        @Bean
        @Primary
        RecordingNotificationHandler recordingNotificationHandler() {
            return new RecordingNotificationHandler();
        }
    }

    static final class RecordingNotificationHandler implements NotificationHandler {
        private final AtomicInteger orderAttempts = new AtomicInteger();
        private final AtomicInteger inventoryAttempts = new AtomicInteger();
        private final AtomicReference<OrderCreatedEvent> lastOrder = new AtomicReference<>();
        private final AtomicReference<InventoryAdjustedEvent> lastInventoryAdjustment = new AtomicReference<>();
        private final AtomicBoolean failOrderNotifications = new AtomicBoolean();

        @Override
        public void handle(OrderCreatedEvent event) {
            orderAttempts.incrementAndGet();
            if (failOrderNotifications.get()) {
                throw new IllegalStateException("simulated notification outage");
            }
            lastOrder.set(event);
        }

        @Override
        public void handle(InventoryAdjustedEvent event) {
            inventoryAttempts.incrementAndGet();
            lastInventoryAdjustment.set(event);
        }

        void reset() {
            orderAttempts.set(0);
            inventoryAttempts.set(0);
            lastOrder.set(null);
            lastInventoryAdjustment.set(null);
            failOrderNotifications.set(false);
        }

        void failOrderNotifications() {
            failOrderNotifications.set(true);
        }

        OrderCreatedEvent lastOrder() {
            return lastOrder.get();
        }

        InventoryAdjustedEvent lastInventoryAdjustment() {
            return lastInventoryAdjustment.get();
        }

        boolean awaitOrderAttempts(int expectedAttempts) throws InterruptedException {
            return await(() -> orderAttempts.get() >= expectedAttempts);
        }

        boolean awaitInventoryAttempts(int expectedAttempts) throws InterruptedException {
            return await(() -> inventoryAttempts.get() >= expectedAttempts);
        }

        private boolean await(BooleanSupplier condition) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                if (condition.getAsBoolean()) {
                    return true;
                }
                Thread.sleep(50);
            }
            return false;
        }
    }
}
