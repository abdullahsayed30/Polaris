package io.polaris.order.application;

import io.polaris.shared.events.OrderCreatedEvent;

public record OrderCreatedApplicationEvent(OrderCreatedEvent event) {
}
