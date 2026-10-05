package io.polaris.inventory.adapter.out.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.polaris.inventory.application.domain.model.InventoryItem;
import io.polaris.inventory.application.port.out.InventoryStock;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryPersistenceAdapter implements InventoryStock {
    private final InventoryItemRepository items;
    public InventoryPersistenceAdapter(InventoryItemRepository items) {
        this.items = items;
    }
    public List<InventoryItem> findBySkuIn(Collection<String> skus) {
        return items.findBySkuIn(skus).stream().map(InventoryItemMapper::toDomain).toList();
    }
    public List<InventoryItem> findBySkuInForUpdate(Collection<String> skus) {
        return items.findBySkuInForUpdate(skus).stream().map(InventoryItemMapper::toDomain).toList();
    }
    public void update(Collection<InventoryItem> changed) {
        for (InventoryItem item : changed) {
            var managed = items.findById(item.getId()).orElseThrow();
            InventoryItemMapper.update(item, managed);
        }
        items.flush();
    }
}
