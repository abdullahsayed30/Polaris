package io.polaris.inventory.application.port.in;

public record StockAvailability(
        String sku,
        int requestedQuantity,
        int availableQuantity,
        boolean available) {
}
