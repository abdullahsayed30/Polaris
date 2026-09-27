package io.polaris.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import io.polaris.notification.application.NotificationApplicationService;
import io.polaris.notification.application.NotificationHandler;
import io.polaris.notification.persistence.InboxEvent;
import io.polaris.notification.persistence.InboxEventRepository;
import io.polaris.notification.persistence.InboxStatus;
import io.polaris.shared.events.EventMetadata;
import io.polaris.shared.events.InventoryAdjustedEvent;
import io.polaris.shared.events.OrderCreatedEvent;

class NotificationKafkaListenerTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final NotificationHandler notificationHandler = mock(NotificationHandler.class);
    private final KafkaTemplate<String, NotificationDeadLetterEvent> kafkaTemplate = mock(KafkaTemplate.class);
    private final NotificationDeadLetterPublisher deadLetterPublisher = spy(new NotificationDeadLetterPublisher(
            kafkaTemplate, "polaris.notifications.dlq", Duration.ofSeconds(1)));
    private final InboxEventRepository inboxEvents = mock(InboxEventRepository.class);
    private NotificationKafkaListener listener;

    @BeforeEach
    void setUp() {
        Retry retry = Retry.of(
                "test",
                RetryConfig.custom()
                        .maxAttempts(3)
                        .waitDuration(Duration.ZERO)
                        .retryExceptions(RuntimeException.class)
                        .build());
        when(kafkaTemplate.send(anyString(), anyString(), any(NotificationDeadLetterEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(inboxEvents.saveAndFlush(any(InboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        listener = new NotificationKafkaListener(objectMapper,
                new NotificationApplicationService(notificationHandler, retry, deadLetterPublisher, inboxEvents));
    }

    @Test
    void skipsDuplicateEventIdAfterInboxCommit() throws Exception {
        OrderCreatedEvent event = orderCreatedEvent();
        ConsumerRecord<String, String> record = record(objectMapper.writeValueAsString(event));
        when(inboxEvents.existsById(event.metadata().eventId())).thenReturn(false, true);

        listener.onOrderCreated(record);
        listener.onOrderCreated(record);

        verify(notificationHandler).handle(event);
        verify(inboxEvents).saveAndFlush(any(InboxEvent.class));
        verify(deadLetterPublisher, never()).publish(any());
    }

    @Test
    void retriesTransientHandlerFailureThenMarksInboxProcessed() throws Exception {
        OrderCreatedEvent event = orderCreatedEvent();
        ConsumerRecord<String, String> record = record(objectMapper.writeValueAsString(event));
        AtomicInteger attempts = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("temporary outage");
            }
            return null;
        }).when(notificationHandler).handle(event);
        ArgumentCaptor<InboxEvent> inbox = ArgumentCaptor.forClass(InboxEvent.class);

        listener.onOrderCreated(record);

        assertThat(attempts).hasValue(3);
        verify(inboxEvents).saveAndFlush(inbox.capture());
        assertThat(inbox.getValue().getStatus()).isEqualTo(InboxStatus.PROCESSED);
        verify(deadLetterPublisher, never()).publish(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transitionsManagedInboxReturnedByRepository(boolean handlerFails) throws Exception {
        OrderCreatedEvent event = orderCreatedEvent();
        ConsumerRecord<String, String> record = record(objectMapper.writeValueAsString(event));
        InboxEvent managed = InboxEvent.received(event.metadata(), "OrderCreated",
                record.topic(), record.partition(), record.offset());
        when(inboxEvents.saveAndFlush(any(InboxEvent.class))).thenReturn(managed);
        if (handlerFails) {
            doThrow(new IllegalStateException("handler unavailable")).when(notificationHandler).handle(event);
        }

        listener.onOrderCreated(record);

        ArgumentCaptor<InboxEvent> submitted = ArgumentCaptor.forClass(InboxEvent.class);
        verify(inboxEvents).saveAndFlush(submitted.capture());
        assertThat(submitted.getValue()).isNotSameAs(managed);
        assertThat(submitted.getValue().getStatus()).isEqualTo(InboxStatus.PROCESSING);
        assertThat(managed.getStatus()).isEqualTo(handlerFails ? InboxStatus.DEAD_LETTERED : InboxStatus.PROCESSED);
    }

    @Test
    void routesPoisonPayloadToDeadLetterWithStableSourceCoordinates() {
        ConsumerRecord<String, String> record = record("{not-json");
        ArgumentCaptor<NotificationDeadLetterEvent> deadLetters = ArgumentCaptor.forClass(NotificationDeadLetterEvent.class);

        listener.onOrderCreated(record);
        listener.onOrderCreated(record);

        verify(deadLetterPublisher, times(2)).publish(deadLetters.capture());
        NotificationDeadLetterEvent first = deadLetters.getAllValues().getFirst();
        NotificationDeadLetterEvent second = deadLetters.getAllValues().getLast();
        assertThat(first.metadata().eventId()).isEqualTo(second.metadata().eventId());
        assertThat(first.sourceTopic()).isEqualTo("polaris.orders.created");
        assertThat(first.sourcePartition()).isEqualTo(2);
        assertThat(first.sourceOffset()).isEqualTo(41L);
        assertThat(first.sourceEventId()).isNull();
        assertThat(first.payload()).isEqualTo("{not-json");
        verify(notificationHandler, never()).handle(any(OrderCreatedEvent.class));
    }

    @Test
    void propagatesDeadLetterBrokerFailureSoSourceRecordIsRedelivered() {
        ConsumerRecord<String, String> record = record("{not-json");
        doThrow(new DeadLetterPublicationException(UUID.randomUUID(), new IllegalStateException("broker down")))
                .when(deadLetterPublisher)
                .publish(any());

        assertThatThrownBy(() -> listener.onOrderCreated(record))
                .isInstanceOf(DeadLetterPublicationException.class)
                .hasMessageContaining("Failed to publish dead-letter event");
    }

    @Test
    void legacyOrderIsProcessedOnceOnRedeliveryWithStableMetadata() throws Exception {
        String payload = legacyFixture("legacy-order-created.json");
        OrderCreatedEvent original = objectMapper.readValue(payload, OrderCreatedEvent.class);
        ConsumerRecord<String, String> record = record(payload);
        when(inboxEvents.existsById(any())).thenReturn(false, true);

        listener.onOrderCreated(record);
        listener.onOrderCreated(record);

        ArgumentCaptor<OrderCreatedEvent> events = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(notificationHandler).handle(events.capture());
        OrderCreatedEvent received = events.getValue();
        assertThat(received.orderId()).isEqualTo(original.orderId());
        assertThat(received.items()).isEqualTo(original.items());
        assertThat(received.metadata().occurredAt()).isEqualTo(original.createdAt());
        assertThat(received.metadata().correlationId()).isEqualTo(record.key());
        verify(inboxEvents, times(2)).existsById(received.metadata().eventId());
        verify(deadLetterPublisher, never()).publish(any(NotificationDeadLetterEvent.class));
    }

    @Test
    void legacyInventoryIsProcessedOnceOnRedeliveryWithStableMetadata() throws Exception {
        String payload = legacyFixture("legacy-inventory-adjusted.json");
        InventoryAdjustedEvent original = objectMapper.readValue(payload, InventoryAdjustedEvent.class);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("polaris.inventory.adjusted", 1, 10L,
                original.orderId().toString(), payload);
        when(inboxEvents.existsById(any())).thenReturn(false, true);

        listener.onInventoryAdjusted(record);
        listener.onInventoryAdjusted(record);

        ArgumentCaptor<InventoryAdjustedEvent> events = ArgumentCaptor.forClass(InventoryAdjustedEvent.class);
        verify(notificationHandler).handle(events.capture());
        InventoryAdjustedEvent received = events.getValue();
        assertThat(received.items()).isEqualTo(original.items());
        assertThat(received.metadata().occurredAt()).isEqualTo(original.adjustedAt());
        verify(inboxEvents, times(2)).existsById(received.metadata().eventId());
        verify(deadLetterPublisher, never()).publish(any(NotificationDeadLetterEvent.class));
    }

    @Test
    void currentInventoryMetadataIsPreserved() throws Exception {
        Instant occurredAt = Instant.parse("2026-05-11T12:00:00Z");
        InventoryAdjustedEvent event = new InventoryAdjustedEvent(
                EventMetadata.causedBy(1, occurredAt, "correlation", UUID.randomUUID()), UUID.randomUUID(),
                List.of(new InventoryAdjustedEvent.Item("SKU-1", -2, 8)), occurredAt);

        listener.onInventoryAdjusted(new ConsumerRecord<>("polaris.inventory.adjusted", 0, 1L,
                "key", objectMapper.writeValueAsString(event)));

        verify(notificationHandler).handle(event);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.00", "12345678901234567890.1234567890123456789000"})
    void currentOrderPreservesExactPricePrecisionAndScale(String price) throws Exception {
        OrderCreatedEvent original = orderCreatedEvent();
        OrderCreatedEvent event = new OrderCreatedEvent(original.metadata(), original.orderId(),
                original.customerId(), original.status(),
                List.of(new OrderCreatedEvent.Item(UUID.randomUUID(), "SKU-1", 1, new BigDecimal(price))),
                original.createdAt());

        listener.onOrderCreated(record(objectMapper.writeValueAsString(event)));

        ArgumentCaptor<OrderCreatedEvent> received = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(notificationHandler).handle(received.capture());
        BigDecimal actual = received.getValue().items().getFirst().unitPrice();
        assertThat(actual.unscaledValue()).isEqualTo(new BigDecimal(price).unscaledValue());
        assertThat(actual.scale()).isEqualTo(new BigDecimal(price).scale());
        assertThat(received.getValue()).isEqualTo(event);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.00", "12345678901234567890.1234567890123456789000"})
    void legacyOrderPreservesExactPricePrecisionAndScale(String price) throws Exception {
        String payload = legacyFixture("legacy-order-created.json").replace("19.99", price);

        listener.onOrderCreated(record(payload));

        ArgumentCaptor<OrderCreatedEvent> received = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(notificationHandler).handle(received.capture());
        BigDecimal actual = received.getValue().items().getFirst().unitPrice();
        assertThat(actual.unscaledValue()).isEqualTo(new BigDecimal(price).unscaledValue());
        assertThat(actual.scale()).isEqualTo(new BigDecimal(price).scale());
    }

    @Test
    void legacyIdentityIncludesTopicPartitionAndOffset() throws Exception {
        ObjectNode payload = objectMapper.valueToTree(orderCreatedEvent());
        payload.remove("metadata");
        String json = objectMapper.writeValueAsString(payload);
        listener.onOrderCreated(new ConsumerRecord<>("orders", 0, 1L, "key", json));
        listener.onOrderCreated(new ConsumerRecord<>("orders", 0, 2L, "key", json));
        listener.onOrderCreated(new ConsumerRecord<>("orders", 1, 1L, "key", json));
        listener.onOrderCreated(new ConsumerRecord<>("other-orders", 0, 1L, "key", json));

        ArgumentCaptor<OrderCreatedEvent> events = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(notificationHandler, times(4)).handle(events.capture());
        assertThat(events.getAllValues()).extracting(event -> event.metadata().eventId()).doesNotHaveDuplicates();
    }

    @Test
    void explicitlyNullOrMalformedMetadataIsNotTreatedAsLegacy() throws Exception {
        ObjectNode payload = objectMapper.valueToTree(orderCreatedEvent());
        payload.putNull("metadata");
        listener.onOrderCreated(record(objectMapper.writeValueAsString(payload)));
        payload.set("metadata", objectMapper.readTree("{}"));
        listener.onOrderCreated(record(objectMapper.writeValueAsString(payload)));
        payload.set("metadata", objectMapper.valueToTree(orderCreatedEvent().metadata()));
        ((ObjectNode) payload.get("metadata")).put("version", 0);
        listener.onOrderCreated(record(objectMapper.writeValueAsString(payload)));

        verify(notificationHandler, never()).handle(any(OrderCreatedEvent.class));
        verify(inboxEvents, never()).saveAndFlush(any());
        verify(deadLetterPublisher, times(3)).publish(any(NotificationDeadLetterEvent.class));
    }

    @Test
    void unsupportedOrderMetadataVersionIsDeadLettered() throws Exception {
        ObjectNode payload = objectMapper.valueToTree(orderCreatedEvent());
        ((ObjectNode) payload.get("metadata")).put("version", 2);

        listener.onOrderCreated(record(objectMapper.writeValueAsString(payload)));

        verify(notificationHandler, never()).handle(any(OrderCreatedEvent.class));
        verify(inboxEvents, never()).saveAndFlush(any());
        ArgumentCaptor<NotificationDeadLetterEvent> deadLetter = ArgumentCaptor.forClass(NotificationDeadLetterEvent.class);
        verify(deadLetterPublisher).publish(deadLetter.capture());
        assertThat(deadLetter.getValue().errorMessage()).contains("Unsupported event metadata version: 2");
    }

    @Test
    void unsupportedInventoryMetadataVersionIsDeadLettered() throws Exception {
        Instant occurredAt = Instant.parse("2026-05-11T12:00:00Z");
        InventoryAdjustedEvent event = new InventoryAdjustedEvent(
                EventMetadata.initial(2, occurredAt, "correlation"), UUID.randomUUID(),
                List.of(new InventoryAdjustedEvent.Item("SKU-1", -2, 8)), occurredAt);

        listener.onInventoryAdjusted(new ConsumerRecord<>("polaris.inventory.adjusted", 0, 1L,
                "key", objectMapper.writeValueAsString(event)));

        verify(notificationHandler, never()).handle(any(InventoryAdjustedEvent.class));
        verify(inboxEvents, never()).saveAndFlush(any());
        ArgumentCaptor<NotificationDeadLetterEvent> deadLetter = ArgumentCaptor.forClass(NotificationDeadLetterEvent.class);
        verify(deadLetterPublisher).publish(deadLetter.capture());
        assertThat(deadLetter.getValue().errorMessage()).contains("Unsupported event metadata version: 2");
    }

    @Test
    void missingLegacyOccurrenceTimeIsDeadLettered() throws Exception {
        ObjectNode payload = objectMapper.valueToTree(orderCreatedEvent());
        payload.remove("metadata");
        payload.remove("createdAt");

        listener.onOrderCreated(record(objectMapper.writeValueAsString(payload)));

        verify(notificationHandler, never()).handle(any(OrderCreatedEvent.class));
        verify(deadLetterPublisher).publish(any(NotificationDeadLetterEvent.class));
    }

    @Test
    void exhaustedHandlerIsDeadLetteredBeforeInboxBecomesTerminal() throws Exception {
        OrderCreatedEvent event = orderCreatedEvent();
        doThrow(new IllegalStateException("handler unavailable")).when(notificationHandler).handle(event);
        ArgumentCaptor<InboxEvent> inbox = ArgumentCaptor.forClass(InboxEvent.class);

        listener.onOrderCreated(record(objectMapper.writeValueAsString(event)));

        verify(notificationHandler, times(3)).handle(event);
        verify(inboxEvents).saveAndFlush(inbox.capture());
        assertThat(inbox.getValue().getStatus()).isEqualTo(InboxStatus.DEAD_LETTERED);
        verify(deadLetterPublisher).publish(any(NotificationDeadLetterEvent.class));
    }

    @Test
    void failedDeadLetterForValidEventEscapesWithoutMarkingInboxTerminal() throws Exception {
        OrderCreatedEvent event = orderCreatedEvent();
        ConsumerRecord<String, String> record = record(objectMapper.writeValueAsString(event));
        doThrow(new IllegalStateException("handler unavailable")).when(notificationHandler).handle(event);
        doThrow(new DeadLetterPublicationException(UUID.randomUUID(), new IllegalStateException("broker down")))
                .when(deadLetterPublisher).publish(any(NotificationDeadLetterEvent.class));
        ArgumentCaptor<InboxEvent> inbox = ArgumentCaptor.forClass(InboxEvent.class);

        assertThatThrownBy(() -> listener.onOrderCreated(record)).isInstanceOf(DeadLetterPublicationException.class);

        verify(inboxEvents).saveAndFlush(inbox.capture());
        assertThat(inbox.getValue().getStatus()).isEqualTo(InboxStatus.PROCESSING);
    }

    private String legacyFixture(String name) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/events/" + name)) {
            return new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ConsumerRecord<String, String> record(String payload) {
        return new ConsumerRecord<>("polaris.orders.created", 2, 41L, "order-1", payload);
    }

    private OrderCreatedEvent orderCreatedEvent() {
        Instant now = Instant.now();
        return new OrderCreatedEvent(
                EventMetadata.initial(OrderCreatedEvent.EVENT_VERSION, now, "request-1"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                OrderCreatedEvent.OrderStatus.CONFIRMED,
                List.of(new OrderCreatedEvent.Item(
                        UUID.randomUUID(),
                        "SKU-1",
                        1,
                        new BigDecimal("1.00"))),
                now);
    }
}
