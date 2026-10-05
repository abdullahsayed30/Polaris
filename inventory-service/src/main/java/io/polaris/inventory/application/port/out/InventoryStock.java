package io.polaris.inventory.application.port.out;

import java.util.Collection;
import java.util.List;

import io.polaris.inventory.application.domain.model.InventoryItem;

/** Lock every requested SKU in deterministic order and retain locks through the caller's transaction. */
public interface InventoryStock {
    List<InventoryItem> findBySkuIn(Collection<String> skus);
    List<InventoryItem> findBySkuInForUpdate(Collection<String> skus);
    void update(Collection<InventoryItem> items);
}
