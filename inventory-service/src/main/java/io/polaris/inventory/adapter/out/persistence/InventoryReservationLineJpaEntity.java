package io.polaris.inventory.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity(name = "InventoryReservationLine")
@Table(name = "inventory_reservation_lines")
public class InventoryReservationLineJpaEntity {
    @Id
    @Column(nullable = false, updatable = false)
    UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    InventoryReservationJpaEntity reservation;

    @Column(nullable = false, length = 128, updatable = false)
    String sku;

    @Column(name = "requested_quantity", nullable = false, updatable = false)
    int requestedQuantity;

    @Column(name = "reserved_quantity", nullable = false, updatable = false)
    int reservedQuantity;

    @Column(name = "remaining_quantity", nullable = false, updatable = false)
    int remainingQuantity;

    @Column(name = "released_available_quantity")
    Integer releasedAvailableQuantity;

    protected InventoryReservationLineJpaEntity() {
    }

    public UUID getId() {
        return id;
    }

    public InventoryReservationJpaEntity getReservation() {
        return reservation;
    }

    public String getSku() {
        return sku;
    }

    public int getRequestedQuantity() {
        return requestedQuantity;
    }

    public int getReservedQuantity() {
        return reservedQuantity;
    }

    public int getRemainingQuantity() {
        return remainingQuantity;
    }

    public Integer getReleasedAvailableQuantity() {
        return releasedAvailableQuantity;
    }
}
