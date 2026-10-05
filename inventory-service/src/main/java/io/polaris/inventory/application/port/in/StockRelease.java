package io.polaris.inventory.application.port.in;

public record StockRelease(
        String sku,
        int releasedQuantity,
        int availableQuantity) {
}
