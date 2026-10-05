package io.polaris.order.adapter.out.persistence;

import java.util.Objects;

import io.polaris.order.application.domain.model.OrderRequest;

/** Keeps persistence identity and child ownership separate from business behavior. */
public final class OrderRequestMapper {
    private OrderRequestMapper() {
    }

    public static OrderRequest toDomain(OrderRequestJpaEntity entity) {
        return OrderRequest.restore(entity.id,
                entity.customerId,
                entity.idempotencyKey,
                entity.requestHash,
                entity.orderId,
                entity.status,
                entity.createdAt,
                entity.updatedAt,
                entity.version);
    }

    public static OrderRequestJpaEntity toEntity(OrderRequest model) {
        OrderRequestJpaEntity entity = new OrderRequestJpaEntity();
        entity.id = model.getId();
        entity.customerId = model.getCustomerId();
        entity.idempotencyKey = model.getIdempotencyKey();
        entity.requestHash = model.getRequestHash();
        entity.orderId = model.getOrderId();
        entity.status = model.getStatus();
        entity.createdAt = model.getCreatedAt();
        entity.updatedAt = model.getUpdatedAt();
        entity.version = model.getVersion();
        return entity;
    }

    static void update(OrderRequest model, OrderRequestJpaEntity entity) {
        if (!Objects.equals(model.getId(), entity.id)) {
            throw new IllegalArgumentException("Cannot change persisted identity");
        }
        if (model.getVersion() != entity.version) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(entity.getClass(), entity.id);
        }
        entity.customerId = model.getCustomerId();
        entity.idempotencyKey = model.getIdempotencyKey();
        entity.requestHash = model.getRequestHash();
        entity.status = model.getStatus();
        entity.orderId = model.getOrderId();
    }
}
