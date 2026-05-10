package io.polaris.notification.messaging;

import java.time.Instant;

public record NotificationDeadLetterEvent(
        String sourceTopic,
        String sourceKey,
        String eventType,
        String payload,
        String errorType,
        String errorMessage,
        Instant failedAt
) {
}
