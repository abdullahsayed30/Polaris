package io.polaris.shared.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InventoryAdjustedEvent(
        UUID orderId,
        List<Item> items,
        Instant adjustedAt
) {
    public record Item(
            String sku,
            int quantityChanged,
            int availableQuantity
    ) {
    }
}
