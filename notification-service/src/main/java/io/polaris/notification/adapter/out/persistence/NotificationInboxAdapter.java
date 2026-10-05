package io.polaris.notification.adapter.out.persistence;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.polaris.notification.application.port.in.NotificationDelivery;
import io.polaris.notification.application.port.out.NotificationInbox;
import io.polaris.shared.events.EventMetadata;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class NotificationInboxAdapter implements NotificationInbox {
    private final InboxEventRepository events;
    public NotificationInboxAdapter(InboxEventRepository events) {
        this.events = events;
    }
    public boolean contains(UUID id) {
        return events.existsById(id);
    }
    public Receipt receive(EventMetadata metadata, NotificationDelivery delivery) {
        // Assigned IDs may cause merge: retain the managed instance returned by saveAndFlush.
        InboxEvent managed = events.saveAndFlush(InboxEvent.received(metadata, delivery.eventType(),
                delivery.topic(), delivery.partition(), delivery.offset()));
        return new Receipt() {
            public void markProcessed() {
                managed.markProcessed();
            }
            public void markDeadLettered(Exception failure) {
                managed.markDeadLettered(failure);
            }
        };
    }
}
