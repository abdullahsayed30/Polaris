package io.polaris.order.adapter.out.messaging;

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

import io.polaris.order.OutboxPublisherProperties;
import io.polaris.order.adapter.out.persistence.OutboxEvent;
import io.polaris.order.adapter.out.persistence.OutboxEventRepository;
import io.polaris.order.adapter.out.persistence.OutboxStatus;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEvents;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxPublisherProperties properties;
    private final MeterRegistry meterRegistry;

    public OutboxPublisher(
            OutboxEventRepository outboxEvents,
            KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper,
            OutboxPublisherProperties properties,
            MeterRegistry meterRegistry) {
        this.outboxEvents = outboxEvents;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void publishReady() {
        Instant now = Instant.now();
        for (OutboxEvent event : outboxEvents.findReady(now, PageRequest.of(0, properties.batchSize()))) {
            publish(event, now);
        }
    }

    private void publish(OutboxEvent event, Instant now) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload());
            kafkaTemplate.send(event.getTopic(), event.getMessageKey(), payload)
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            event.markPublished(Instant.now());
            meterRegistry.counter("polaris.outbox.publications", "outcome", "published").increment();
            log.info(
                    "Outbox event published eventId={} eventType={} attempts={}",
                    event.getEventId(),
                    event.getEventType(),
                    event.getAttempts());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            recordFailure(event, now, ex);
        } catch (Exception ex) {
            recordFailure(event, now, ex);
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
