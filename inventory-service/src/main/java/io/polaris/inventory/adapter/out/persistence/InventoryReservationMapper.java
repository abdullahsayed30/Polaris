package io.polaris.inventory.adapter.out.persistence;

import java.util.ArrayList;
import java.util.Objects;

import io.polaris.inventory.application.domain.model.InventoryReservation;

/** Keeps persistence identity and child ownership separate from business behavior. */
public final class InventoryReservationMapper {
    private InventoryReservationMapper() {
    }

    public static InventoryReservation toDomain(InventoryReservationJpaEntity entity) {
        return InventoryReservation.restore(entity.orderId,
                entity.status,
                entity.lines.stream().map(InventoryReservationLineMapper::toDomain).toList(),
                entity.createdAt,
                entity.updatedAt,
                entity.version);
    }

    public static InventoryReservationJpaEntity toEntity(InventoryReservation model) {
        InventoryReservationJpaEntity entity = new InventoryReservationJpaEntity();
        entity.orderId = model.getOrderId();
        entity.status = model.getStatus();
        entity.lines = new ArrayList<>(model.getLines().stream().map(InventoryReservationLineMapper::toEntity).toList());
        entity.lines.forEach(child -> child.reservation = entity);
        entity.createdAt = model.getCreatedAt();
        entity.updatedAt = model.getUpdatedAt();
        entity.version = model.getVersion();
        return entity;
    }

    static void update(InventoryReservation model, InventoryReservationJpaEntity entity) {
        if (!Objects.equals(model.getOrderId(), entity.orderId)) {
            throw new IllegalArgumentException("Cannot change persisted identity");
        }
        if (model.getVersion() != entity.version) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(entity.getClass(), entity.orderId);
        }
        entity.status = model.getStatus();
        entity.lines.removeIf(stored -> model.getLines().stream().noneMatch(item -> item.getId().equals(stored.id)));
        for (var item : model.getLines()) {
            var stored = entity.lines.stream().filter(candidate -> candidate.id.equals(item.getId())).findFirst();
            if (stored.isPresent()) {
                InventoryReservationLineMapper.update(item, stored.orElseThrow());
            } else {
                var added = InventoryReservationLineMapper.toEntity(item);
                added.reservation = entity;
                entity.lines.add(added);
            }
        }
    }
}
