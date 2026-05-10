package io.polaris.shared.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderCreatedEvent(
        UUID orderId,
        UUID customerId,
        OrderStatus status,
        List<Item> items,
        Instant createdAt
) {
    public enum OrderStatus {
        PENDING,
        CONFIRMED,
        CANCELLED
    }

    public record Item(
            UUID itemId,
            String sku,
            int quantity,
            BigDecimal unitPrice
    ) {
    }
}
