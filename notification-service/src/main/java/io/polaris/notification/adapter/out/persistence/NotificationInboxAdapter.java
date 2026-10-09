package io.polaris.notification.adapter.out.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import io.polaris.notification.adapter.out.observability.DurableTelemetry;
import io.polaris.notification.application.port.in.NotificationDelivery;
import io.polaris.notification.application.port.out.NotificationInbox;
import io.polaris.shared.events.EventMetadata;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class NotificationInboxAdapter implements NotificationInbox {
    private static final Duration FRESHNESS_MAXIMUM = Duration.ofDays(7);
    private final InboxEventRepository events;
    private final DurableTelemetry telemetry;
    private final MeterRegistry metrics;
    public NotificationInboxAdapter(InboxEventRepository events, DurableTelemetry telemetry, MeterRegistry metrics) {
        this.telemetry = telemetry;
        this.metrics = metrics;
        metrics.counter("polaris.notification.duplicates");
        metrics.counter("polaris.notification.invalid.occurrence.time");
        for (String type : new String[]{"OrderCreated", "InventoryAdjusted"}) {
            for (String outcome : new String[]{"processed", "dead_lettered"}) {
                metrics.counter("polaris.notification.completions", "event_type", type, "outcome", outcome);
            }
            Timer.builder("polaris.notification.freshness").tag("event_type", type)
                    .publishPercentileHistogram().minimumExpectedValue(Duration.ofMillis(1))
                    .maximumExpectedValue(FRESHNESS_MAXIMUM)
                    .serviceLevelObjectives(Duration.ofHours(1), Duration.ofHours(6), Duration.ofHours(12),
                            Duration.ofDays(1), Duration.ofDays(3), FRESHNESS_MAXIMUM)
                    .register(metrics);
            metrics.counter("polaris.notification.freshness.overflow", "event_type", type);
        }
        this.events = events;
    }
    public boolean contains(UUID id) {
        boolean duplicate = telemetry.database("SELECT", "inbox_events", () -> events.existsById(id));
        if (duplicate) {
            try (var session = telemetry.child("notification.duplicate", id.toString())) {
                session.outcome("suppressed");
                metrics.counter("polaris.notification.duplicates").increment();
            }
        }
        return duplicate;
    }
    public Receipt receive(EventMetadata metadata, NotificationDelivery delivery) {
        var session = telemetry.child("notification.process", metadata.eventId().toString());
        session.closeWithTransaction();
        // Assigned IDs may cause merge: retain the managed instance returned by saveAndFlush.
        InboxEvent managed = telemetry.database("INSERT", "inbox_events",
                () -> events.saveAndFlush(InboxEvent.received(metadata, delivery.eventType(),
                        delivery.topic(), delivery.partition(), delivery.offset())));
        return new Receipt() {
            public void markProcessed() {
                telemetry.databaseWrite("UPDATE", "inbox_events", () -> {
                    managed.markProcessed();
                    events.flush();
                });
                session.outcome("simulated_processed");
                DurableTelemetry.afterCommit(() -> {
                    metrics.counter("polaris.notification.completions", "event_type", delivery.eventType(),
                            "outcome", "processed").increment();
                    Duration age = Duration.between(metadata.occurredAt(), Instant.now());
                    if (!age.isNegative()) {
                        metrics.timer("polaris.notification.freshness", "event_type", delivery.eventType()).record(age);
                        if (age.compareTo(FRESHNESS_MAXIMUM) > 0) {
                            metrics.counter("polaris.notification.freshness.overflow", "event_type", delivery.eventType()).increment();
                        }
                    } else {
                        metrics.counter("polaris.notification.invalid.occurrence.time").increment();
                    }
                });
            }
            public void markDeadLettered(Exception failure) {
                telemetry.databaseWrite("UPDATE", "inbox_events", () -> {
                    managed.markDeadLettered(failure);
                    events.flush();
                });
                session.outcome("dead_lettered");
                session.error(failure);
                DurableTelemetry.afterCommit(() -> metrics.counter("polaris.notification.completions",
                        "event_type", delivery.eventType(), "outcome", "dead_lettered").increment());
            }
        };
    }
}
