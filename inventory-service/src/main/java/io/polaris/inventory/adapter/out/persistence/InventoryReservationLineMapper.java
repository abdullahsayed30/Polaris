package io.polaris.inventory.adapter.out.persistence;

import java.util.Objects;

import io.polaris.inventory.application.domain.model.InventoryReservationLine;

/** Keeps persistence identity and child ownership separate from business behavior. */
public final class InventoryReservationLineMapper {
    private InventoryReservationLineMapper() {
    }

    public static InventoryReservationLine toDomain(InventoryReservationLineJpaEntity entity) {
        return InventoryReservationLine.restore(entity.id,
                entity.sku,
                entity.requestedQuantity,
                entity.reservedQuantity,
                entity.remainingQuantity,
                entity.releasedAvailableQuantity);
    }

    public static InventoryReservationLineJpaEntity toEntity(InventoryReservationLine model) {
        InventoryReservationLineJpaEntity entity = new InventoryReservationLineJpaEntity();
        entity.id = model.getId();
        entity.sku = model.getSku();
        entity.requestedQuantity = model.getRequestedQuantity();
        entity.reservedQuantity = model.getReservedQuantity();
        entity.remainingQuantity = model.getRemainingQuantity();
        entity.releasedAvailableQuantity = model.getReleasedAvailableQuantity();
        return entity;
    }

    static void update(InventoryReservationLine model, InventoryReservationLineJpaEntity entity) {
        if (!Objects.equals(model.getId(), entity.id)) {
            throw new IllegalArgumentException("Cannot change persisted identity");
        }
        entity.sku = model.getSku();
        entity.requestedQuantity = model.getRequestedQuantity();
        entity.reservedQuantity = model.getReservedQuantity();
        entity.remainingQuantity = model.getRemainingQuantity();
        entity.releasedAvailableQuantity = model.getReleasedAvailableQuantity();
    }
}
