package io.polaris.inventory.application.port.out;

import java.util.Optional;
import java.util.UUID;

import io.polaris.inventory.application.domain.model.InventoryReservation;

public interface ReservationStore {
    int insertIfAbsent(UUID orderId);
    Optional<InventoryReservation> findForUpdate(UUID orderId);
    void update(InventoryReservation reservation);
}
