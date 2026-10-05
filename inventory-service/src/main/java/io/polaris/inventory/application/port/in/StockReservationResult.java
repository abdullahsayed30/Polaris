package io.polaris.inventory.application.port.in;

import java.util.List;

import io.polaris.inventory.application.domain.model.ReservationStatus;

public record StockReservationResult(
        boolean reserved,
        InventoryDecisionReason reason,
        ReservationStatus status,
        List<StockReservation> items) {
}
