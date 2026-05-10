package io.polaris.order.api;

import io.polaris.order.domain.Order;
import io.polaris.order.domain.OrderStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        UUID customerId,
        OrderStatus status,
        List<OrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt
) {
    static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getStatus(),
                order.getItems().stream()
                        .map(OrderItemResponse::from)
                        .toList(),
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }
}
