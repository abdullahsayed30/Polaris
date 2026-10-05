package io.polaris.order.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRequestRepository extends JpaRepository<OrderRequestJpaEntity, UUID> {
    Optional<OrderRequestJpaEntity> findByOrderId(UUID orderId);

    // Deterministic id and customer/key both identify this intent; either constraint can win an insert race.
    @Modifying
    @Query(value = """
            INSERT INTO order_requests (
                id, customer_id, idempotency_key, request_hash, status, created_at, updated_at, version
            ) VALUES (
                :id, :customerId, :idempotencyKey, :requestHash,
                'PROCESSING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
            )
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("customerId") UUID customerId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select request from OrderRequest request
            where request.customerId = :customerId and request.idempotencyKey = :idempotencyKey
            """)
    Optional<OrderRequestJpaEntity> findForUpdate(
            @Param("customerId") UUID customerId,
            @Param("idempotencyKey") String idempotencyKey);
}
