package io.polaris.inventory.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import io.polaris.inventory.OutboxPublisherProperties;
import io.polaris.inventory.adapter.out.persistence.OutboxEvent;
import io.polaris.inventory.adapter.out.persistence.OutboxEventRepository;
import io.polaris.inventory.adapter.out.persistence.OutboxStatus;

class OutboxPublisherTest {
    @Test
    void inventoryEventSurvivesBrokerOutageAndPublishesOnRetry() {
        OutboxEventRepository outboxEvents = mock(OutboxEventRepository.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        OutboxEvent event = OutboxEvent.pending(
                UUID.randomUUID(),
                "order-1",
                "InventoryAdjusted",
                1,
                "polaris.inventory.adjusted",
                "order-1",
                "{\"orderId\":\"order-1\"}",
                Instant.now().minusSeconds(1));
        when(outboxEvents.findReady(any(), any())).thenReturn(List.of(event));
        when(kafkaTemplate.send(eq(event.getTopic()), eq(event.getMessageKey()), any(JsonNode.class)))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker unavailable")))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        OutboxPublisher publisher = new OutboxPublisher(
                outboxEvents,
                kafkaTemplate,
                new ObjectMapper(),
                new OutboxPublisherProperties(
                        10,
                        3,
                        Duration.ofMillis(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)),
                new SimpleMeterRegistry());

        publisher.publishReady();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.RETRY);
        assertThat(event.getAttempts()).isOne();

        publisher.publishReady();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getAttempts()).isEqualTo(2);
    }
}
