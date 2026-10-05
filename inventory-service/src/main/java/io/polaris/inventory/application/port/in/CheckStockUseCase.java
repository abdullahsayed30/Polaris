package io.polaris.inventory.application.port.in;

import java.util.List;
import java.util.UUID;

public interface CheckStockUseCase {
    StockCheckResult checkStock(UUID orderId, List<InventoryLine> lines);
}
