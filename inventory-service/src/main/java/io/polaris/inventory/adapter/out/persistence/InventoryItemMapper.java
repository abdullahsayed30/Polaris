package io.polaris.inventory.adapter.out.persistence;

import java.util.Objects;

import io.polaris.inventory.application.domain.model.InventoryItem;

/** Keeps persistence identity and child ownership separate from business behavior. */
public final class InventoryItemMapper {
    private InventoryItemMapper() {
    }

    public static InventoryItem toDomain(InventoryItemJpaEntity entity) {
        return InventoryItem.restore(entity.id,
                entity.sku,
                entity.availableQuantity,
                entity.createdAt,
                entity.updatedAt,
                entity.version);
    }

    public static InventoryItemJpaEntity toEntity(InventoryItem model) {
        InventoryItemJpaEntity entity = new InventoryItemJpaEntity();
        entity.id = model.getId();
        entity.sku = model.getSku();
        entity.availableQuantity = model.getAvailableQuantity();
        entity.createdAt = model.getCreatedAt();
        entity.updatedAt = model.getUpdatedAt();
        entity.version = model.getVersion();
        return entity;
    }

    static void update(InventoryItem model, InventoryItemJpaEntity entity) {
        if (!Objects.equals(model.getId(), entity.id)) {
            throw new IllegalArgumentException("Cannot change persisted identity");
        }
        if (model.getVersion() != entity.version) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(entity.getClass(), entity.id);
        }
        entity.sku = model.getSku();
        entity.availableQuantity = model.getAvailableQuantity();
    }
}
