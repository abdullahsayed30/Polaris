package io.polaris.notification.adapter.out.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

import io.polaris.notification.adapter.out.observability.DurableTelemetry;
import io.polaris.notification.application.port.in.NotificationDelivery;
import io.polaris.notification.application.port.out.NotificationDeadLetters;
import io.polaris.shared.events.EventMetadata;

@Component
public class NotificationDeadLetterPublisher implements NotificationDeadLetters {
    private static final Logger log = LoggerFactory.getLogger(NotificationDeadLetterPublisher.class);
    private final KafkaTemplate<String, NotificationDeadLetterEvent> kafkaTemplate;
    private final String topic;
    private final Duration sendTimeout;
    private final MeterRegistry metrics;
    private final DurableTelemetry telemetry;

    public NotificationDeadLetterPublisher(
            KafkaTemplate<String, NotificationDeadLetterEvent> kafkaTemplate,
            @Value("${polaris.kafka.topics.notifications-dlq}") String topic,
            @Value("${polaris.notifications.dlq.send-timeout:10s}") Duration sendTimeout, DurableTelemetry telemetry,
            MeterRegistry metrics) {
        this.telemetry = telemetry;
        this.metrics = metrics;
        metrics.counter("polaris.notification.dlq.publications", "outcome", "acknowledged");
        metrics.counter("polaris.notification.dlq.publications", "outcome", "failed");
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void publish(NotificationDelivery delivery, EventMetadata sourceMetadata, Exception failure) {
        Instant failedAt = Instant.now();
        UUID sourceEventId = sourceMetadata == null ? null : sourceMetadata.eventId();
        String identity = sourceEventId == null
                ? delivery.topic() + ":" + delivery.partition() + ":" + delivery.offset()
                : sourceEventId.toString();
        EventMetadata metadata = new EventMetadata(
                UUID.nameUUIDFromBytes(("notification-dlq:" + identity).getBytes(StandardCharsets.UTF_8)),
                NotificationDeadLetterEvent.EVENT_VERSION,
                failedAt,
                sourceMetadata == null ? delivery.key() : sourceMetadata.correlationId(),
                sourceEventId);
        publish(new NotificationDeadLetterEvent(
                metadata, delivery.topic(), delivery.partition(), delivery.offset(), delivery.key(),
                sourceEventId, sourceMetadata == null ? null : sourceMetadata.version(), delivery.eventType(),
                delivery.payload(), failure.getClass().getName(), failure.getMessage(), failedAt));
        log.error("Notification event dead-lettered eventId={} eventType={} sourceTopic={} sourceOffset={} dlqTopic={}",
                sourceEventId, delivery.eventType(), delivery.topic(), delivery.offset(), topic, failure);
    }

    public void publish(NotificationDeadLetterEvent event) {
        try (var session = telemetry.attempt("notification.dlq.publish.attempt", event.metadata().eventId().toString())) {
            try {
                kafkaTemplate.send(topic, event.sourceKey(), event)
                        .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                metrics.counter("polaris.notification.dlq.publications", "outcome", "acknowledged").increment();
                session.outcome("broker_acknowledged");
            } catch (InterruptedException ex) {
                session.error(ex);
                session.outcome("interrupted");
                metrics.counter("polaris.notification.dlq.publications", "outcome", "failed").increment();
                Thread.currentThread().interrupt();
                throw new DeadLetterPublicationException(event.metadata().eventId(), ex);
            } catch (Exception ex) {
                session.error(ex);
                session.outcome("failed");
                metrics.counter("polaris.notification.dlq.publications", "outcome", "failed").increment();
                throw new DeadLetterPublicationException(event.metadata().eventId(), ex);
            }
        }
    }

    String topic() {
        return topic;
    }
}
