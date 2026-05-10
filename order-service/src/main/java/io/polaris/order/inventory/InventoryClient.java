package io.polaris.order.inventory;

import io.polaris.order.domain.Order;

public interface InventoryClient {
    StockCheckResult checkStock(Order order);
}
