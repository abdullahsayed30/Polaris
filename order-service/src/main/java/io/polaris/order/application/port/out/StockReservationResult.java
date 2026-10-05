package io.polaris.order.application.port.out;

public record StockReservationResult(boolean reserved, InventoryDecision reason) {
}
