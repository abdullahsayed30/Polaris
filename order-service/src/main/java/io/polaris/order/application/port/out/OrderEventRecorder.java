package io.polaris.order.application.port.out;

import io.polaris.shared.events.OrderCreatedEvent;

/** Records an event durably as part of the caller's local transaction. */
public interface OrderEventRecorder {
    void enqueue(OrderCreatedEvent event);
}
