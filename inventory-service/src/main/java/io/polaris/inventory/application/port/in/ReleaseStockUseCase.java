package io.polaris.inventory.application.port.in;

import java.util.UUID;

public interface ReleaseStockUseCase {
    StockReleaseResult releaseStock(UUID orderId);
}
