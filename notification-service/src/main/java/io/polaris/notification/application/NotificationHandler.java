package io.polaris.notification.application;

import io.polaris.shared.events.InventoryAdjustedEvent;
import io.polaris.shared.events.OrderCreatedEvent;

public interface NotificationHandler {
    void handle(OrderCreatedEvent event);

    void handle(InventoryAdjustedEvent event);
}
