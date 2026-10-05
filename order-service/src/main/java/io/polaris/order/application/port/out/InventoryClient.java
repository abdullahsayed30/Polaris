package io.polaris.order.application.port.out;

import io.polaris.order.application.domain.model.Order;

public interface InventoryClient {
    StockCheckResult checkStock(Order order);

    StockReservationResult reserveStock(Order order);
}
