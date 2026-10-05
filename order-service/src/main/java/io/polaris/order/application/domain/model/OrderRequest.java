package io.polaris.order.application.domain.model;

import java.time.Instant;
import java.util.UUID;

public class OrderRequest {

    private UUID id;

    private UUID customerId;

    private String idempotencyKey;

    private String requestHash;

    private UUID orderId;

    private OrderRequestStatus status;

    private Instant createdAt;

    private Instant updatedAt;

    private long version;

    private OrderRequest() {
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

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public OrderRequestStatus getStatus() {
        return status;
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
    public static OrderRequest restore(UUID id, UUID customerId, String idempotencyKey, String requestHash, UUID orderId,
            OrderRequestStatus status, Instant createdAt, Instant updatedAt, long version) {
        OrderRequest result = new OrderRequest();
        result.id = id;
        result.customerId = customerId;
        result.idempotencyKey = idempotencyKey;
        result.requestHash = requestHash;
        result.orderId = orderId;
        result.status = status;
        result.createdAt = createdAt;
        result.updatedAt = updatedAt;
        result.version = version;
        return result;
    }
}
