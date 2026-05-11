package io.polaris.inventory.application;

public record StockAvailability(
        String sku,
        int requestedQuantity,
        int availableQuantity,
        boolean available) {
}
