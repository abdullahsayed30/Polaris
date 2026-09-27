package io.polaris.inventory.application;

import io.polaris.shared.events.InventoryAdjustedEvent;

/** Records an event durably as part of the caller's local transaction. */
public interface InventoryEventRecorder {
    void enqueue(InventoryAdjustedEvent event);
}
