package io.polaris.notification.application.domain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.polaris.notification.application.port.in.NotificationDelivery;
import io.polaris.notification.application.port.in.ProcessNotificationUseCase;
import io.polaris.notification.application.port.out.NotificationDeadLetters;
import io.polaris.notification.application.port.out.NotificationHandler;
import io.polaris.notification.application.port.out.NotificationInbox;
import io.polaris.notification.application.port.out.NotificationRetry;
import io.polaris.shared.events.EventMetadata;
import io.polaris.shared.events.InventoryAdjustedEvent;
import io.polaris.shared.events.OrderCreatedEvent;

@Service
public class NotificationApplicationService implements ProcessNotificationUseCase {
    private static final Logger log = LoggerFactory.getLogger(NotificationApplicationService.class);

    private final NotificationHandler notificationHandler;
    private final NotificationRetry notificationRetry;
    private final NotificationDeadLetters deadLetters;
    private final NotificationInbox inboxEvents;

    public NotificationApplicationService(
            NotificationHandler notificationHandler,
            NotificationRetry notificationRetry,
            NotificationDeadLetters deadLetters,
            NotificationInbox inboxEvents) {
        this.notificationHandler = notificationHandler;
        this.notificationRetry = notificationRetry;
        this.deadLetters = deadLetters;
        this.inboxEvents = inboxEvents;
    }

    @Transactional
    public void process(NotificationDelivery delivery, OrderCreatedEvent event) {
        process(delivery, event.metadata(), () -> notificationHandler.handle(event));
    }

    @Transactional
    public void process(NotificationDelivery delivery, InventoryAdjustedEvent event) {
        process(delivery, event.metadata(), () -> notificationHandler.handle(event));
    }

    public void reject(NotificationDelivery delivery, Exception failure) {
        deadLetters.publish(delivery, null, failure);
    }

    private void process(NotificationDelivery delivery, EventMetadata metadata, Runnable handler) {
        if (inboxEvents.contains(metadata.eventId())) {
            log.info("Duplicate notification event skipped eventId={} eventType={}",
                    metadata.eventId(), delivery.eventType());
            return;
        }

        NotificationInbox.Receipt inbox = inboxEvents.receive(metadata, delivery);
        try {
            notificationRetry.execute(handler);
            inbox.markProcessed();
        } catch (Exception ex) {
            deadLetters.publish(delivery, metadata, ex);
            inbox.markDeadLettered(ex);
        }
    }
}
