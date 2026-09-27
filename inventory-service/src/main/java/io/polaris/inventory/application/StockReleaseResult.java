package io.polaris.inventory.application;

import java.util.List;

import io.polaris.inventory.domain.ReservationStatus;

public record StockReleaseResult(
        boolean released,
        InventoryDecisionReason reason,
        ReservationStatus status,
        List<StockRelease> items) {
}
