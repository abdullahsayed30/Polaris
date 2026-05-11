package io.polaris.inventory.application;

public record StockReservation(
        String sku,
        int requestedQuantity,
        int reservedQuantity,
        int remainingQuantity,
        boolean reserved) {
}
