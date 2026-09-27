package io.polaris.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.polaris.notification.application.NotificationDelivery;
import io.polaris.notification.application.NotificationHandler;
import io.polaris.notification.messaging.DeadLetterPublicationException;
import io.polaris.notification.messaging.NotificationDeadLetterEvent;
import io.polaris.notification.messaging.NotificationDeadLetterPublisher;
import io.polaris.notification.messaging.NotificationKafkaListener;
import io.polaris.notification.persistence.InboxEventRepository;
import io.polaris.notification.persistence.InboxStatus;
import io.polaris.shared.events.EventMetadata;
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

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("polaris_notifications")
            .withUsername("polaris_notification")
            .withPassword("polaris_notification");

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    RecordingNotificationHandler notificationHandler;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    InboxEventRepository inboxEvents;

    @Autowired
    NotificationKafkaListener listener;

    @MockitoSpyBean
    NotificationDeadLetterPublisher deadLetterPublisher;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void setUp() {
        notificationHandler.reset();
        inboxEvents.deleteAll();
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
        assertThat(awaitInboxStatus(orderEvent.metadata().eventId(), InboxStatus.PROCESSED)).isTrue();
        assertThat(awaitInboxStatus(inventoryEvent.metadata().eventId(), InboxStatus.PROCESSED)).isTrue();
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
        assertThat(deadLetter.sourceEventId()).isEqualTo(orderEvent.metadata().eventId());
        assertThat(deadLetter.metadata().causationId()).isEqualTo(orderEvent.metadata().eventId());
        assertThat(awaitInboxStatus(orderEvent.metadata().eventId(), InboxStatus.DEAD_LETTERED)).isTrue();
    }

    @Test
    void duplicateDeliveryIsHandledOnlyOnce() throws Exception {
        OrderCreatedEvent orderEvent = orderCreatedEvent();

        kafkaTemplate.send(ORDER_CREATED_TOPIC, orderEvent.orderId().toString(), orderEvent)
                .get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(ORDER_CREATED_TOPIC, orderEvent.orderId().toString(), orderEvent)
                .get(10, TimeUnit.SECONDS);

        assertThat(notificationHandler.awaitOrderAttempts(1)).isTrue();
        Thread.sleep(500);
        assertThat(notificationHandler.orderAttempts()).isOne();
        assertThat(awaitInboxStatus(orderEvent.metadata().eventId(), InboxStatus.PROCESSED)).isTrue();
    }

    @Test
    void failedDeadLetterRollsBackInboxAndAllowsRedelivery() throws Exception {
        OrderCreatedEvent event = orderCreatedEvent();
        ConsumerRecord<String, String> record = new ConsumerRecord<>(ORDER_CREATED_TOPIC, 0, 500L,
                event.orderId().toString(), objectMapper.writeValueAsString(event));
        notificationHandler.failOrderNotifications();
        doThrow(new DeadLetterPublicationException(UUID.randomUUID(), new IllegalStateException("broker down")))
                .when(deadLetterPublisher).publish(any(NotificationDelivery.class), any(EventMetadata.class), any());

        assertThatThrownBy(() -> listener.onOrderCreated(record)).isInstanceOf(DeadLetterPublicationException.class);
        assertThat(inboxEvents.findById(event.metadata().eventId())).isEmpty();

        doCallRealMethod().when(deadLetterPublisher)
                .publish(any(NotificationDelivery.class), any(EventMetadata.class), any());
        listener.onOrderCreated(record);

        assertThat(inboxEvents.findById(event.metadata().eventId())).isPresent()
                .get().extracting(inbox -> inbox.getStatus()).isEqualTo(InboxStatus.DEAD_LETTERED);
        assertThat(notificationHandler.orderAttempts()).isEqualTo(6);
        assertThat(awaitDeadLetterEvent(event.orderId()).sourceEventId()).isEqualTo(event.metadata().eventId());
    }

    private boolean awaitInboxStatus(UUID eventId, InboxStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (inboxEvents.findById(eventId).filter(inbox -> inbox.getStatus() == expected).isPresent()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
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
        Instant now = Instant.now();
        return new OrderCreatedEvent(
                EventMetadata.initial(OrderCreatedEvent.EVENT_VERSION, now, "integration-test"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                OrderCreatedEvent.OrderStatus.CONFIRMED,
                List.of(new OrderCreatedEvent.Item(
                        UUID.randomUUID(),
                        "SKU-COFFEE-001",
                        2,
                        new BigDecimal("19.99"))),
                now);
    }

    private InventoryAdjustedEvent inventoryAdjustedEvent(UUID orderId) {
        Instant now = Instant.now();
        return new InventoryAdjustedEvent(
                EventMetadata.initial(InventoryAdjustedEvent.EVENT_VERSION, now, "integration-test"),
                orderId,
                List.of(new InventoryAdjustedEvent.Item("SKU-COFFEE-001", -2, 8)),
                now);
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

        int orderAttempts() {
            return orderAttempts.get();
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
