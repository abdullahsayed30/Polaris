package io.polaris.inventory.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.polaris.inventory.adapter.out.observability.DurableTelemetry;
import io.polaris.inventory.application.domain.model.InventoryReservation;
import io.polaris.inventory.application.port.out.ReservationStore;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class ReservationPersistenceAdapter implements ReservationStore {
    private final InventoryReservationRepository reservations;
    private final DurableTelemetry telemetry;
    public ReservationPersistenceAdapter(InventoryReservationRepository reservations, DurableTelemetry telemetry) {
        this.telemetry = telemetry;
        this.reservations = reservations;
    }
    public int insertIfAbsent(UUID orderId) {
        return telemetry.database("INSERT", "inventory_reservations", () -> reservations.insertIfAbsent(orderId));
    }
    public Optional<InventoryReservation> findForUpdate(UUID orderId) {
        return telemetry.database("SELECT", "inventory_reservations",
                () -> reservations.findForUpdate(orderId).map(InventoryReservationMapper::toDomain));
    }
    public void update(InventoryReservation reservation) {
        telemetry.databaseWrite("UPDATE", "inventory_reservations", () -> {
            var managed = reservations.findById(reservation.getOrderId()).orElseThrow();
            InventoryReservationMapper.update(reservation, managed);
            reservations.flush();
        });
    }
}
