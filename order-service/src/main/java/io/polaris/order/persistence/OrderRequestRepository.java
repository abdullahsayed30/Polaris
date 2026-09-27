package io.polaris.order.persistence;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.polaris.order.domain.OrderRequest;

public interface OrderRequestRepository extends JpaRepository<OrderRequest, UUID> {
    Optional<OrderRequest> findByOrderId(UUID orderId);

    @Modifying
    @Query(value = """
            INSERT INTO order_requests (
                id, customer_id, idempotency_key, request_hash, status, created_at, updated_at, version
            ) VALUES (
                :id, :customerId, :idempotencyKey, :requestHash,
                'PROCESSING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
            )
            ON CONFLICT (customer_id, idempotency_key) DO NOTHING
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
    Optional<OrderRequest> findForUpdate(
            @Param("customerId") UUID customerId,
            @Param("idempotencyKey") String idempotencyKey);
}
