package io.polaris.order.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import io.polaris.order.OutboxPublisherProperties;
import io.polaris.order.adapter.out.persistence.OutboxEvent;
import io.polaris.order.adapter.out.persistence.OutboxEventRepository;
import io.polaris.order.adapter.out.persistence.OutboxStatus;

class OutboxPublisherTest {
    private final OutboxEventRepository outboxEvents = mock(OutboxEventRepository.class);
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OutboxPublisherProperties properties = new OutboxPublisherProperties(
            10,
            3,
            Duration.ofMillis(1),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1));
    private final OutboxPublisher publisher = new OutboxPublisher(
            outboxEvents,
            kafkaTemplate,
            objectMapper,
            properties,
            new SimpleMeterRegistry());

    @Test
    void retainsFailedSendForRetryThenMarksTheSameEventPublished() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.pending(
                eventId,
                UUID.randomUUID().toString(),
                "OrderCreated",
                1,
                "polaris.orders.created",
                "order-1",
                "{\"metadata\":{\"eventId\":\"" + eventId + "\"}}",
                Instant.now().minusSeconds(1));
        when(outboxEvents.findReady(any(), any())).thenReturn(List.of(event));
        when(kafkaTemplate.send(eq(event.getTopic()), eq(event.getMessageKey()), any(JsonNode.class)))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker unavailable")))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishReady();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.RETRY);
        assertThat(event.getAttempts()).isOne();
        assertThat(event.getLastError()).contains("broker unavailable");
        assertThat(event.getNextAttemptAt()).isAfter(Instant.now().minusSeconds(1));

        publisher.publishReady();

        assertThat(event.getEventId()).isEqualTo(eventId);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getAttempts()).isEqualTo(2);
        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getLastError()).isNull();

        ArgumentCaptor<JsonNode> payloads = ArgumentCaptor.forClass(JsonNode.class);
        verify(kafkaTemplate, org.mockito.Mockito.times(2))
                .send(eq(event.getTopic()), eq(event.getMessageKey()), payloads.capture());
        assertThat(payloads.getAllValues())
                .extracting(payload -> payload.at("/metadata/eventId").asText())
                .containsOnly(eventId.toString());
    }

    @Test
    void movesPoisonOutboxRecordToFailedAfterAttemptLimit() {
        OutboxEvent event = OutboxEvent.pending(
                UUID.randomUUID(),
                "order-1",
                "OrderCreated",
                1,
                "polaris.orders.created",
                "order-1",
                "not-json",
                Instant.now().minusSeconds(1));
        when(outboxEvents.findReady(any(), any())).thenReturn(List.of(event));
        OutboxPublisher oneAttemptPublisher = new OutboxPublisher(
                outboxEvents,
                kafkaTemplate,
                objectMapper,
                new OutboxPublisherProperties(
                        10,
                        1,
                        Duration.ofMillis(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)),
                new SimpleMeterRegistry());

        oneAttemptPublisher.publishReady();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getAttempts()).isOne();
        assertThat(event.getLastError()).contains("JsonParseException");
    }
}
