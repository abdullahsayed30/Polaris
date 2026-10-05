package io.polaris.order.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.polaris.order.application.domain.model.Order;
import io.polaris.order.application.domain.model.OrderStatus;

public record OrderResponse(
        UUID id,
        UUID customerId,
        OrderStatus status,
        List<OrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt) {
    static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getStatus(),
                order.getItems().stream()
                        .map(OrderItemResponse::from)
                        .toList(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
