package io.polaris.inventory.adapter.out.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.polaris.inventory.adapter.out.observability.DurableTelemetry;
import io.polaris.inventory.application.domain.model.InventoryItem;
import io.polaris.inventory.application.port.out.InventoryStock;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryPersistenceAdapter implements InventoryStock {
    private final InventoryItemRepository items;
    private final DurableTelemetry telemetry;
    public InventoryPersistenceAdapter(InventoryItemRepository items, DurableTelemetry telemetry) {
        this.telemetry = telemetry;
        this.items = items;
    }
    public List<InventoryItem> findBySkuIn(Collection<String> skus) {
        return telemetry.database("SELECT", "inventory_items",
                () -> items.findBySkuIn(skus).stream().map(InventoryItemMapper::toDomain).toList());
    }
    public List<InventoryItem> findBySkuInForUpdate(Collection<String> skus) {
        return telemetry.database("SELECT", "inventory_items",
                () -> items.findBySkuInForUpdate(skus).stream().map(InventoryItemMapper::toDomain).toList());
    }
    public void update(Collection<InventoryItem> changed) {
        telemetry.databaseWrite("UPDATE", "inventory_items", () -> {
            for (InventoryItem item : changed) {
                var managed = items.findById(item.getId()).orElseThrow();
                InventoryItemMapper.update(item, managed);
            }
            items.flush();
        });
    }
}
