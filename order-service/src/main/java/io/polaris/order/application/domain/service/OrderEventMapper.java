package io.polaris.order.application.domain.service;

import java.time.Instant;

import io.polaris.order.application.domain.model.Order;
import io.polaris.order.application.domain.model.OrderItem;
import io.polaris.shared.events.EventMetadata;
import io.polaris.shared.events.OrderCreatedEvent;

public final class OrderEventMapper {
    private OrderEventMapper() {
    }

    public static OrderCreatedEvent toOrderCreatedEvent(Order order, String correlationId) {
        Instant occurredAt = Instant.now();
        return new OrderCreatedEvent(
                EventMetadata.initial(
                        OrderCreatedEvent.EVENT_VERSION,
                        occurredAt,
                        correlationId),
                order.getId(),
                order.getCustomerId(),
                OrderCreatedEvent.OrderStatus.valueOf(order.getStatus().name()),
                order.getItems().stream()
                        .map(OrderEventMapper::toItem)
                        .toList(),
                order.getCreatedAt());
    }

    private static OrderCreatedEvent.Item toItem(OrderItem item) {
        return new OrderCreatedEvent.Item(
                item.getId(),
                item.getSku(),
                item.getQuantity(),
                item.getUnitPrice());
    }
}
