package io.polaris.order.application.port.out;

public record StockCheckResult(boolean available, InventoryDecision reason) {
}
