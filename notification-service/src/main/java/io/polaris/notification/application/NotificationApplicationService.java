package io.polaris.notification.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.resilience4j.retry.Retry;

import io.polaris.notification.persistence.InboxEvent;
import io.polaris.notification.persistence.InboxEventRepository;
import io.polaris.shared.events.EventMetadata;
import io.polaris.shared.events.InventoryAdjustedEvent;
import io.polaris.shared.events.OrderCreatedEvent;

@Service
public class NotificationApplicationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationApplicationService.class);

    private final NotificationHandler notificationHandler;
    private final Retry notificationRetry;
    private final NotificationDeadLetters deadLetters;
    private final InboxEventRepository inboxEvents;

    public NotificationApplicationService(
            NotificationHandler notificationHandler,
            Retry notificationRetry,
            NotificationDeadLetters deadLetters,
            InboxEventRepository inboxEvents) {
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
        if (inboxEvents.existsById(metadata.eventId())) {
            log.info("Duplicate notification event skipped eventId={} eventType={}",
                    metadata.eventId(), delivery.eventType());
            return;
        }

        InboxEvent inbox = inboxEvents.saveAndFlush(InboxEvent.received(metadata, delivery.eventType(),
                delivery.topic(), delivery.partition(), delivery.offset()));
        try {
            Retry.decorateRunnable(notificationRetry, handler).run();
            inbox.markProcessed();
        } catch (Exception ex) {
            deadLetters.publish(delivery, metadata, ex);
            inbox.markDeadLettered(ex);
        }
    }
}
