package io.polaris.inventory.adapter.out.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

import io.polaris.inventory.OutboxPublisherProperties;
import io.polaris.inventory.adapter.out.observability.DurableTelemetry;
import io.polaris.inventory.adapter.out.persistence.OutboxEvent;
import io.polaris.inventory.adapter.out.persistence.OutboxEventRepository;
import io.polaris.inventory.adapter.out.persistence.OutboxStatus;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEvents;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxPublisherProperties properties;
    private final MeterRegistry meterRegistry;
    private final DurableTelemetry telemetry;

    public OutboxPublisher(
            OutboxEventRepository outboxEvents,
            KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper,
            OutboxPublisherProperties properties,
            MeterRegistry meterRegistry, DurableTelemetry telemetry) {
        this.telemetry = telemetry;
        this.outboxEvents = outboxEvents;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper.copy()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        for (String outcome : new String[]{"published", "retry", "failed"}) {
            meterRegistry.counter("polaris.outbox.publications", "outcome", outcome);
        }
    }

    @Transactional
    public void publishReady() {
        Instant now = Instant.now();
        for (OutboxEvent event : telemetry.database("SELECT", "outbox_events",
                () -> outboxEvents.findReady(now, PageRequest.of(0, properties.batchSize())))) {
            publish(event, now);
        }
    }

    private void publish(OutboxEvent event, Instant now) {
        try (var session = telemetry.resume("outbox.publish.attempt", event.getTraceContext(), event.getEventId().toString())) {
            try {
                JsonNode payload = objectMapper.readTree(event.getPayload());
                kafkaTemplate.send(event.getTopic(), event.getMessageKey(), payload)
                        .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                event.markPublished(Instant.now());
                session.outcome("broker_acknowledged");
                meterRegistry.counter("polaris.outbox.publications", "outcome", "published").increment();
                log.info("Outbox event published eventId={} eventType={} attempts={}",
                        event.getEventId(), event.getEventType(), event.getAttempts());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                session.error(ex);
                recordFailure(event, now, ex);
            } catch (Exception ex) {
                session.error(ex);
                recordFailure(event, now, ex);
            }
            telemetry.databaseWrite("UPDATE", "outbox_events", outboxEvents::flush);
        }
    }

    private void recordFailure(OutboxEvent event, Instant now, Exception failure) {
        Duration backoff = backoff(event.getAttempts());
        event.markFailed(failureMessage(failure), now.plus(backoff), properties.maxAttempts());
        String outcome = event.getStatus() == OutboxStatus.FAILED ? "failed" : "retry";
        meterRegistry.counter("polaris.outbox.publications", "outcome", outcome).increment();
        log.warn(
                "Outbox publication failed eventId={} eventType={} attempts={} status={} nextAttemptAt={}",
                event.getEventId(),
                event.getEventType(),
                event.getAttempts(),
                event.getStatus(),
                event.getNextAttemptAt(),
                failure);
    }

    private Duration backoff(int completedAttempts) {
        long multiplier = 1L << Math.min(completedAttempts, 30);
        long delay;
        try {
            delay = Math.multiplyExact(properties.initialBackoff().toMillis(), multiplier);
        } catch (ArithmeticException ex) {
            delay = properties.maxBackoff().toMillis();
        }
        return Duration.ofMillis(Math.min(delay, properties.maxBackoff().toMillis()));
    }

    private String failureMessage(Exception failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        String message = cause.getMessage();
        return cause.getClass().getName() + (message == null ? "" : ": " + message);
    }
}
