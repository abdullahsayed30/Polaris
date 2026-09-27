package io.polaris.shared.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InventoryAdjustedEvent(
        EventMetadata metadata,
        UUID orderId,
        List<Item> items,
        Instant adjustedAt) {
    public static final int EVENT_VERSION = 1;

    public record Item(
            String sku,
            int quantityChanged,
            int availableQuantity) {
    }
}
