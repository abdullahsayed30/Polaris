package io.polaris.inventory.application.port.in;

import java.util.List;

public record StockCheckResult(
        boolean available,
        InventoryDecisionReason reason,
        List<StockAvailability> items) {
}
