package io.polaris.order.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.polaris.order.domain.Order;

public interface OrderRepository extends JpaRepository<Order, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findForUpdate(@Param("id") UUID id);

    @Query(value = """
            SELECT id FROM orders WHERE status = 'PENDING' AND reservation_retry_at <= :now
            ORDER BY reservation_retry_at, id
            """, nativeQuery = true)
    List<UUID> findPendingReservationIds(@Param("now") Instant now, Pageable pageable);

    @Modifying
    @Query(value = """
            UPDATE orders SET reservation_retry_at = :retryAt
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    void deferReservation(@Param("id") UUID id, @Param("retryAt") Instant retryAt);

    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsByIdAndCustomerId(UUID id, UUID customerId);
}
