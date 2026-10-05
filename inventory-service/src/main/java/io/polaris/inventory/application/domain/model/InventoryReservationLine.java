package io.polaris.inventory.application.domain.model;

import java.util.Objects;
import java.util.UUID;

public class InventoryReservationLine {

    private UUID id;

    private String sku;

    private int requestedQuantity;

    private int reservedQuantity;

    private int remainingQuantity;

    private Integer releasedAvailableQuantity;

    private InventoryReservationLine() {
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

    public UUID getId() {
        return id;
    }

    /** Rehydrates a stored snapshot without replaying a business transition. */
    public static InventoryReservationLine restore(UUID id, String sku, int requestedQuantity, int reservedQuantity, int remainingQuantity,
            Integer releasedAvailableQuantity) {
        InventoryReservationLine result = new InventoryReservationLine();
        result.id = id;
        result.sku = sku;
        result.requestedQuantity = requestedQuantity;
        result.reservedQuantity = reservedQuantity;
        result.remainingQuantity = remainingQuantity;
        result.releasedAvailableQuantity = releasedAvailableQuantity;
        return result;
    }
}
