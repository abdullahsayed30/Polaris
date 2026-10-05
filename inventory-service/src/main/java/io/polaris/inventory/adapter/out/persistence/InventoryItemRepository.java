package io.polaris.inventory.adapter.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface InventoryItemRepository extends JpaRepository<InventoryItemJpaEntity, UUID> {
    Optional<InventoryItemJpaEntity> findBySku(String sku);

    List<InventoryItemJpaEntity> findBySkuIn(Collection<String> skus);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from InventoryItem item where item.sku in :skus order by item.sku")
    List<InventoryItemJpaEntity> findBySkuInForUpdate(Collection<String> skus);
}
