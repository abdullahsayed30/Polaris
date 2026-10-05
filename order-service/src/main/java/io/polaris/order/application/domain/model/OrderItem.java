package io.polaris.order.application.domain.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public class OrderItem {

    private UUID id;

    private String sku;

    private int quantity;

    private BigDecimal unitPrice;

    private OrderItem() {
    }

    private OrderItem(UUID id, String sku, int quantity, BigDecimal unitPrice) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.sku = Objects.requireNonNull(sku, "sku must not be null");
        this.quantity = quantity;
        this.unitPrice = Objects.requireNonNull(unitPrice, "unitPrice must not be null");
    }

    public static OrderItem create(String sku, int quantity, BigDecimal unitPrice) {
        return new OrderItem(UUID.randomUUID(), sku, quantity, unitPrice);
    }

    public UUID getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    /** Rehydrates a stored snapshot without replaying a business transition. */
    public static OrderItem restore(UUID id, String sku, int quantity, BigDecimal unitPrice) {
        OrderItem result = new OrderItem();
        result.id = id;
        result.sku = sku;
        result.quantity = quantity;
        result.unitPrice = unitPrice;
        return result;
    }
}
