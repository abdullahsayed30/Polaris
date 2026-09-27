package io.polaris.shared.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EventMetadata(
        UUID eventId,
        int version,
        Instant occurredAt,
        String correlationId,
        UUID causationId) {
    public EventMetadata {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
    }

    public static EventMetadata initial(int version, Instant occurredAt, String correlationId) {
        return new EventMetadata(UUID.randomUUID(), version, occurredAt, correlationId, null);
    }

    public static EventMetadata causedBy(
            int version,
            Instant occurredAt,
            String correlationId,
            UUID causationId) {
        return new EventMetadata(UUID.randomUUID(), version, occurredAt, correlationId, causationId);
    }
}
