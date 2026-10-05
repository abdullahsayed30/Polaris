package io.polaris.inventory.application.port.in;

public record StockReservation(
        String sku,
        int requestedQuantity,
        int reservedQuantity,
        int remainingQuantity,
        boolean reserved) {
}
