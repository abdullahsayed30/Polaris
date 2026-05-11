package io.polaris.inventory.application;

import java.util.List;

public record StockCheckResult(
        boolean available,
        InventoryDecisionReason reason,
        List<StockAvailability> items) {
}
