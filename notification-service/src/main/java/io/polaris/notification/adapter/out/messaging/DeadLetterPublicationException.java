package io.polaris.notification.adapter.out.messaging;

import java.util.UUID;

public class DeadLetterPublicationException extends RuntimeException {
    public DeadLetterPublicationException(UUID eventId, Throwable cause) {
        super("Failed to publish dead-letter event " + eventId, cause);
    }
}
