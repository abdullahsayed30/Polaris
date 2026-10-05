package io.polaris.notification.application.port.in;

import io.polaris.shared.events.InventoryAdjustedEvent;
import io.polaris.shared.events.OrderCreatedEvent;

public interface ProcessNotificationUseCase {
    void process(NotificationDelivery delivery, OrderCreatedEvent event);
    void process(NotificationDelivery delivery, InventoryAdjustedEvent event);
    void reject(NotificationDelivery delivery, Exception failure);
}
