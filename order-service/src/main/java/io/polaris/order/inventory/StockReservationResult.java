package io.polaris.order.inventory;

import io.polaris.inventory.grpc.InventoryDecision;

public record StockReservationResult(boolean reserved, InventoryDecision reason) {
}
