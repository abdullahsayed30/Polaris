package io.polaris.notification.application;

import io.polaris.shared.events.EventMetadata;

public interface NotificationDeadLetters {
    /** Returns only after durable delivery; failures must propagate to roll back processing. */
    void publish(NotificationDelivery delivery, EventMetadata sourceMetadata, Exception failure);
}
