package io.polaris.inventory.application;

public record StockRelease(
        String sku,
        int releasedQuantity,
        int availableQuantity) {
}
