package io.polaris.inventory.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "inventory_reservations")
public class InventoryReservation {
    @Id
    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReservationStatus status;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sku ASC")
    private List<InventoryReservationLine> lines = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected InventoryReservation() {
    }

    public void recordDecision(ReservationStatus decision, List<InventoryReservationLine> decisionLines) {
        if (status != ReservationStatus.PROCESSING) {
            throw new IllegalStateException("Reservation for order " + orderId + " already has a decision");
        }
        if (decision != ReservationStatus.RESERVED && decision != ReservationStatus.REJECTED) {
            throw new IllegalArgumentException("Initial reservation decision must be RESERVED or REJECTED");
        }
        decisionLines.forEach(line -> line.assignTo(this));
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

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
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
}
