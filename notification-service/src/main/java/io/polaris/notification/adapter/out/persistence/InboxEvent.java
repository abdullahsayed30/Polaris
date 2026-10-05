package io.polaris.notification.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.polaris.shared.events.EventMetadata;

@Entity
@Table(name = "inbox_events")
public class InboxEvent {
    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "event_version", nullable = false, updatable = false)
    private int eventVersion;

    @Column(name = "source_topic", nullable = false, updatable = false)
    private String sourceTopic;

    @Column(name = "source_partition", nullable = false, updatable = false)
    private int sourcePartition;

    @Column(name = "source_offset", nullable = false, updatable = false)
    private long sourceOffset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InboxStatus status;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    protected InboxEvent() {
    }

    private InboxEvent(EventMetadata metadata, String eventType, String topic, int partition, long offset) {
        eventId = metadata.eventId();
        this.eventType = eventType;
        eventVersion = metadata.version();
        sourceTopic = topic;
        sourcePartition = partition;
        sourceOffset = offset;
        status = InboxStatus.PROCESSING;
        receivedAt = Instant.now();
    }

    public static InboxEvent received(
            EventMetadata metadata,
            String eventType,
            String topic,
            int partition,
            long offset) {
        return new InboxEvent(metadata, eventType, topic, partition, offset);
    }

    public void markProcessed() {
        status = InboxStatus.PROCESSED;
        processedAt = Instant.now();
        lastError = null;
    }

    public void markDeadLettered(Exception failure) {
        status = InboxStatus.DEAD_LETTERED;
        processedAt = Instant.now();
        String message = failure.getClass().getName() + ": " + failure.getMessage();
        lastError = message.length() <= 2000 ? message : message.substring(0, 2000);
    }

    public UUID getEventId() {
        return eventId;
    }

    public InboxStatus getStatus() {
        return status;
    }
}
