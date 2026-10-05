package io.polaris.order.adapter.out.persistence;

import java.util.Objects;

import io.polaris.order.application.domain.model.OrderItem;

/** Keeps persistence identity and child ownership separate from business behavior. */
public final class OrderItemMapper {
    private OrderItemMapper() {
    }

    public static OrderItem toDomain(OrderItemJpaEntity entity) {
        return OrderItem.restore(entity.id,
                entity.sku,
                entity.quantity,
                entity.unitPrice);
    }

    public static OrderItemJpaEntity toEntity(OrderItem model) {
        OrderItemJpaEntity entity = new OrderItemJpaEntity();
        entity.id = model.getId();
        entity.sku = model.getSku();
        entity.quantity = model.getQuantity();
        entity.unitPrice = model.getUnitPrice();
        return entity;
    }

    static void update(OrderItem model, OrderItemJpaEntity entity) {
        if (!Objects.equals(model.getId(), entity.id)) {
            throw new IllegalArgumentException("Cannot change persisted identity");
        }
        entity.sku = model.getSku();
        entity.quantity = model.getQuantity();
        entity.unitPrice = model.getUnitPrice();
    }
}
