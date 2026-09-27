package io.polaris.notification.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;

import io.polaris.shared.events.EventMetadata;

class NotificationDeadLetterPublisherTest {
    @Test
    void throwsWhenBrokerDoesNotAcknowledgeDeadLetter() {
        KafkaTemplate<String, NotificationDeadLetterEvent> kafkaTemplate = mock(KafkaTemplate.class);
        NotificationDeadLetterEvent event = event();
        when(kafkaTemplate.send("polaris.notifications.dlq", event.sourceKey(), event))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker unavailable")));
        NotificationDeadLetterPublisher publisher = new NotificationDeadLetterPublisher(
                kafkaTemplate,
                "polaris.notifications.dlq",
                Duration.ofSeconds(1));

        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(DeadLetterPublicationException.class)
                .hasCauseInstanceOf(java.util.concurrent.ExecutionException.class);
    }

    private NotificationDeadLetterEvent event() {
        Instant now = Instant.now();
        UUID sourceEventId = UUID.randomUUID();
        return new NotificationDeadLetterEvent(
                EventMetadata.causedBy(1, now, "request-1", sourceEventId),
                "polaris.orders.created",
                0,
                1L,
                "order-1",
                sourceEventId,
                1,
                "OrderCreated",
                "{}",
                IllegalStateException.class.getName(),
                "failure",
                now);
    }
}
