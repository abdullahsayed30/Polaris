package io.polaris.order.adapter.out.persistence;

import java.util.ArrayList;
import java.util.Objects;

import io.polaris.order.application.domain.model.Order;

/** Keeps persistence identity and child ownership separate from business behavior. */
public final class OrderMapper {
    private OrderMapper() {
    }

    public static Order toDomain(OrderJpaEntity entity) {
        return Order.restore(entity.id,
                entity.customerId,
                entity.status,
                entity.items.stream().map(OrderItemMapper::toDomain).toList(),
                entity.createdAt,
                entity.updatedAt,
                entity.version);
    }

    public static OrderJpaEntity toEntity(Order model) {
        OrderJpaEntity entity = new OrderJpaEntity();
        entity.id = model.getId();
        entity.customerId = model.getCustomerId();
        entity.status = model.getStatus();
        entity.items = new ArrayList<>(model.getItems().stream().map(OrderItemMapper::toEntity).toList());
        entity.items.forEach(child -> child.order = entity);
        entity.createdAt = model.getCreatedAt();
        entity.updatedAt = model.getUpdatedAt();
        entity.version = model.getVersion();
        return entity;
    }

    static void update(Order model, OrderJpaEntity entity) {
        if (!Objects.equals(model.getId(), entity.id)) {
            throw new IllegalArgumentException("Cannot change persisted identity");
        }
        if (model.getVersion() != entity.version) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(entity.getClass(), entity.id);
        }
        entity.customerId = model.getCustomerId();
        entity.status = model.getStatus();
        entity.items.removeIf(stored -> model.getItems().stream().noneMatch(item -> item.getId().equals(stored.id)));
        for (var item : model.getItems()) {
            var stored = entity.items.stream().filter(candidate -> candidate.id.equals(item.getId())).findFirst();
            if (stored.isPresent()) {
                OrderItemMapper.update(item, stored.orElseThrow());
            } else {
                var added = OrderItemMapper.toEntity(item);
                added.order = entity;
                entity.items.add(added);
            }
        }
    }
}
