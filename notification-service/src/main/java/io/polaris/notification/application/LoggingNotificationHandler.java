package io.polaris.notification.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.polaris.shared.events.InventoryAdjustedEvent;
import io.polaris.shared.events.OrderCreatedEvent;

@Component
public class LoggingNotificationHandler implements NotificationHandler {
    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationHandler.class);

    @Override
    public void handle(OrderCreatedEvent event) {
        log.info(
                "confirmation email sent orderId={} customerId={} status={} itemCount={}",
                event.orderId(),
                event.customerId(),
                event.status(),
                event.items().size());
    }

    @Override
    public void handle(InventoryAdjustedEvent event) {
        log.info(
                "inventory adjustment notification processed orderId={} itemCount={}",
                event.orderId(),
                event.items().size());
    }
}
