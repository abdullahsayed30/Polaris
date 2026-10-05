package io.polaris.inventory.application.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class InventoryReservation {

    private UUID orderId;

    private ReservationStatus status;

    private List<InventoryReservationLine> lines = new ArrayList<>();

    private Instant createdAt;

    private Instant updatedAt;

    private long version;

    private InventoryReservation() {
    }

    public void recordDecision(ReservationStatus decision, List<InventoryReservationLine> decisionLines) {
        if (status != ReservationStatus.PROCESSING) {
            throw new IllegalStateException("Reservation for order " + orderId + " already has a decision");
        }
        if (decision != ReservationStatus.RESERVED && decision != ReservationStatus.REJECTED) {
            throw new IllegalArgumentException("Initial reservation decision must be RESERVED or REJECTED");
        }
        lines.addAll(decisionLines);
        status = decision;
    }

    public void release() {
        if (status == ReservationStatus.RELEASED) {
            return;
        }
        if (status != ReservationStatus.RESERVED) {
            throw new IllegalStateException("Only a RESERVED reservation can be released");
        }
        status = ReservationStatus.RELEASED;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public List<InventoryReservationLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    /** Rehydrates a stored snapshot without replaying a business transition. */
    public static InventoryReservation restore(UUID orderId, ReservationStatus status, List<InventoryReservationLine> lines,
            Instant createdAt, Instant updatedAt, long version) {
        InventoryReservation result = new InventoryReservation();
        result.orderId = orderId;
        result.status = status;
        result.lines = new ArrayList<>(lines);
        result.createdAt = createdAt;
        result.updatedAt = updatedAt;
        result.version = version;
        return result;
    }
}
