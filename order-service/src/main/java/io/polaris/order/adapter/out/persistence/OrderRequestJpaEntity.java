package io.polaris.order.adapter.out.persistence;

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

import io.polaris.order.application.domain.model.OrderRequestStatus;

@Entity(name = "OrderRequest")
@Table(name = "order_requests")
public class OrderRequestJpaEntity {
    @Id
    @Column(nullable = false, updatable = false)
    UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    UUID customerId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    String requestHash;

    @Column(name = "order_id", unique = true)
    UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    OrderRequestStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @Version
    @Column(nullable = false)
    long version;

    protected OrderRequestJpaEntity() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
