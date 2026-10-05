package io.polaris.order.adapter.in.web;

import java.math.BigDecimal;
import java.util.UUID;

import io.polaris.order.application.domain.model.OrderItem;

public record OrderItemResponse(
        UUID id,
        String sku,
        int quantity,
        BigDecimal unitPrice) {
    static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getId(),
                item.getSku(),
                item.getQuantity(),
                item.getUnitPrice());
    }
}
