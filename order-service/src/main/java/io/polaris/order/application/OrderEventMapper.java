package io.polaris.order.application;

import io.polaris.order.domain.Order;
import io.polaris.order.domain.OrderItem;
import io.polaris.shared.events.OrderCreatedEvent;

public final class OrderEventMapper {
    private OrderEventMapper() {
    }

    public static OrderCreatedEvent toOrderCreatedEvent(Order order) {
        return new OrderCreatedEvent(
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
