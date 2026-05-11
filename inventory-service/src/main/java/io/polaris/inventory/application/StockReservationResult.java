package io.polaris.inventory.application;

import java.util.List;

public record StockReservationResult(
        boolean reserved,
        InventoryDecisionReason reason,
        List<StockReservation> items) {
}
