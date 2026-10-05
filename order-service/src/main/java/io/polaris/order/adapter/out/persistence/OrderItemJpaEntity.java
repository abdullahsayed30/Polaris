package io.polaris.order.adapter.out.persistence;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity(name = "OrderItem")
@Table(name = "order_items")
public class OrderItemJpaEntity {
    @Id
    UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    OrderJpaEntity order;

    @Column(nullable = false, length = 128)
    String sku;

    @Column(nullable = false)
    int quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 2)
    BigDecimal unitPrice;

    protected OrderItemJpaEntity() {
    }

    public UUID getId() {
        return id;
    }

    public OrderJpaEntity getOrder() {
        return order;
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
}
