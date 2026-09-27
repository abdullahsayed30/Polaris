package io.polaris.inventory.persistence;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.polaris.inventory.domain.InventoryReservation;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {
    @Modifying
    @Query(value = """
            INSERT INTO inventory_reservations (order_id, status, created_at, updated_at, version)
            VALUES (:orderId, 'PROCESSING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
            ON CONFLICT (order_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("orderId") UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select reservation from InventoryReservation reservation where reservation.orderId = :orderId")
    Optional<InventoryReservation> findForUpdate(@Param("orderId") UUID orderId);
}
