package io.polaris.inventory.domain;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "inventory_reservation_lines")
public class InventoryReservationLine {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private InventoryReservation reservation;

    @Column(nullable = false, length = 128, updatable = false)
    private String sku;

    @Column(name = "requested_quantity", nullable = false, updatable = false)
    private int requestedQuantity;

    @Column(name = "reserved_quantity", nullable = false, updatable = false)
    private int reservedQuantity;

    @Column(name = "remaining_quantity", nullable = false, updatable = false)
    private int remainingQuantity;

    @Column(name = "released_available_quantity")
    private Integer releasedAvailableQuantity;

    protected InventoryReservationLine() {
    }

    private InventoryReservationLine(
            String sku,
            int requestedQuantity,
            int reservedQuantity,
            int remainingQuantity) {
        this.id = UUID.randomUUID();
        this.sku = Objects.requireNonNull(sku, "sku must not be null");
        this.requestedQuantity = requestedQuantity;
        this.reservedQuantity = reservedQuantity;
        this.remainingQuantity = remainingQuantity;
    }

    public static InventoryReservationLine create(
            String sku,
            int requestedQuantity,
            int reservedQuantity,
            int remainingQuantity) {
        return new InventoryReservationLine(sku, requestedQuantity, reservedQuantity, remainingQuantity);
    }

    void assignTo(InventoryReservation reservation) {
        this.reservation = Objects.requireNonNull(reservation, "reservation must not be null");
    }

    public void recordRelease(int availableQuantity) {
        if (reservedQuantity <= 0) {
            throw new IllegalStateException("A rejected reservation line cannot be released");
        }
        if (releasedAvailableQuantity != null) {
            throw new IllegalStateException("Reservation line has already been released");
        }
        releasedAvailableQuantity = availableQuantity;
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
