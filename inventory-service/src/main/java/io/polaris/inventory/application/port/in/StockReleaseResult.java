package io.polaris.inventory.application.port.in;

import java.util.List;

import io.polaris.inventory.application.domain.model.ReservationStatus;

public record StockReleaseResult(
        boolean released,
        InventoryDecisionReason reason,
        ReservationStatus status,
        List<StockRelease> items) {
}
