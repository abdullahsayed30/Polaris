package io.polaris.notification.adapter.out.messaging;

import java.time.Instant;
import java.util.UUID;

import io.polaris.shared.events.EventMetadata;

public record NotificationDeadLetterEvent(
        EventMetadata metadata,
        String sourceTopic,
        int sourcePartition,
        long sourceOffset,
        String sourceKey,
        UUID sourceEventId,
        Integer sourceEventVersion,
        String eventType,
        String payload,
        String errorType,
        String errorMessage,
        Instant failedAt) {
    public static final int EVENT_VERSION = 1;
}
