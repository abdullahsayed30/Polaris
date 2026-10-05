package io.polaris.notification.application.port.out;

import java.util.UUID;

import io.polaris.notification.application.port.in.NotificationDelivery;
import io.polaris.shared.events.EventMetadata;

/** Receipt and outcome belong to the caller's transaction, including rollback on failed DLQ delivery. */
public interface NotificationInbox {
    boolean contains(UUID eventId);
    Receipt receive(EventMetadata metadata, NotificationDelivery delivery);
    interface Receipt {
        void markProcessed();
        void markDeadLettered(Exception failure);
    }
}
