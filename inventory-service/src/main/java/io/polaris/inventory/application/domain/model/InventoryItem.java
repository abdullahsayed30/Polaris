package io.polaris.inventory.application.domain.model;

import java.time.Instant;
import java.util.UUID;

public class InventoryItem {

    private UUID id;

    private String sku;

    private int availableQuantity;

    private Instant createdAt;

    private Instant updatedAt;

    private long version;

    private InventoryItem() {
    }

    private InventoryItem(String sku, int availableQuantity) {
        this.id = UUID.randomUUID();
        this.sku = sku;
        this.availableQuantity = availableQuantity;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static InventoryItem create(String sku, int availableQuantity) {
        if (availableQuantity < 0) {
            throw new IllegalArgumentException("availableQuantity must be non-negative");
        }
        return new InventoryItem(sku, availableQuantity);
    }

    public boolean canReserve(int quantity) {
        return quantity > 0 && availableQuantity >= quantity;
    }

    public void reserve(int quantity) {
        if (!canReserve(quantity)) {
            throw new IllegalStateException("Insufficient stock for SKU " + sku);
        }
        availableQuantity -= quantity;
    }

    public void release(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        availableQuantity = Math.addExact(availableQuantity, quantity);
    }

    public UUID getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    /** Rehydrates a stored snapshot without replaying a business transition. */
    public static InventoryItem restore(UUID id, String sku, int availableQuantity, Instant createdAt, Instant updatedAt, long version) {
        InventoryItem result = new InventoryItem();
        result.id = id;
        result.sku = sku;
        result.availableQuantity = availableQuantity;
        result.createdAt = createdAt;
        result.updatedAt = updatedAt;
        result.version = version;
        return result;
    }
}
