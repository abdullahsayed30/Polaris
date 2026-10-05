package io.polaris.order.application.port.in;

import java.util.UUID;

public class OrderNotFoundException extends RuntimeException {
    private final UUID orderId;

    public OrderNotFoundException(UUID orderId) {
        super("Order %s was not found".formatted(orderId));
        this.orderId = orderId;
    }

    public UUID getOrderId() {
        return orderId;
    }
}
