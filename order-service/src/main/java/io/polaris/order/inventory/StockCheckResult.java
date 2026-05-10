package io.polaris.order.inventory;

public record StockCheckResult(boolean available, String reason) {
}
