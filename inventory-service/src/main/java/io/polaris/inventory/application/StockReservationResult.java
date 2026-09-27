package io.polaris.inventory.application;

import java.util.List;

import io.polaris.inventory.domain.ReservationStatus;

public record StockReservationResult(
        boolean reserved,
        InventoryDecisionReason reason,
        ReservationStatus status,
        List<StockReservation> items) {
}
