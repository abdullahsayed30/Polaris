package io.polaris.inventory.application;

import io.polaris.shared.events.InventoryAdjustedEvent;

public record InventoryAdjustedApplicationEvent(InventoryAdjustedEvent event) {
}
