package io.polaris.inventory.application.port.in;

import java.util.List;
import java.util.UUID;

public interface ReserveStockUseCase {
    StockReservationResult reserveStock(UUID orderId, List<InventoryLine> lines);
}
