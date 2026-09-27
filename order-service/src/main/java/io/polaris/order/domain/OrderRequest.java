package io.polaris.order.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "order_requests")
public class OrderRequest {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "order_id", unique = true)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderRequestStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected OrderRequest() {
    }

    public void bindOrder(UUID pendingOrderId) {
        if (orderId != null || status != OrderRequestStatus.PROCESSING) {
            throw new IllegalStateException("Order request is already bound");
        }
        orderId = pendingOrderId;
    }

    public void complete(UUID completedOrderId) {
        if (status != OrderRequestStatus.PROCESSING) {
            throw new IllegalStateException("Order request has already completed");
        }
        orderId = completedOrderId;
        status = OrderRequestStatus.COMPLETED;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public OrderRequestStatus getStatus() {
        return status;
    }
}
