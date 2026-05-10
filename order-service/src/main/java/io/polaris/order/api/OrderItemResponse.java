package io.polaris.order.api;

import io.polaris.order.domain.OrderItem;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponse(
        UUID id,
        String sku,
        int quantity,
        BigDecimal unitPrice
) {
    static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getId(),
                item.getSku(),
                item.getQuantity(),
                item.getUnitPrice()
        );
    }
}
