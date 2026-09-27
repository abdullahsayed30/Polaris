package io.polaris.order.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "event_version", nullable = false, updatable = false)
    private int eventVersion;

    @Column(nullable = false, updatable = false)
    private String topic;

    @Column(name = "message_key", nullable = false, updatable = false)
    private String messageKey;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OutboxEvent() {
    }

    private OutboxEvent(
            UUID eventId,
            String aggregateId,
            String eventType,
            int eventVersion,
            String topic,
            String messageKey,
            String payload,
            Instant occurredAt) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = occurredAt;
        this.createdAt = Instant.now();
    }

    public static OutboxEvent pending(
            UUID eventId,
            String aggregateId,
            String eventType,
            int eventVersion,
            String topic,
            String messageKey,
            String payload,
            Instant occurredAt) {
        return new OutboxEvent(
                eventId,
                aggregateId,
                eventType,
                eventVersion,
                topic,
                messageKey,
                payload,
                occurredAt);
    }

    public void markPublished(Instant publishedAt) {
        attempts++;
        status = OutboxStatus.PUBLISHED;
        this.publishedAt = publishedAt;
        lastError = null;
    }

    public void markFailed(String error, Instant nextAttemptAt, int maxAttempts) {
        attempts++;
        lastError = error.length() <= 2000 ? error : error.substring(0, 2000);
        if (attempts >= maxAttempts) {
            status = OutboxStatus.FAILED;
        } else {
            status = OutboxStatus.RETRY;
            this.nextAttemptAt = nextAttemptAt;
        }
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getLastError() {
        return lastError;
    }
}
