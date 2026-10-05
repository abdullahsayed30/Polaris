package io.polaris.order.application.port.out;

import java.util.Optional;
import java.util.UUID;

import io.polaris.order.application.domain.model.OrderRequest;

/** Customer/key uniqueness and locking are atomic within the business transaction. */
public interface OrderRequestStore {
    int insertIfAbsent(UUID id, UUID customerId, String key, String hash);
    Optional<OrderRequest> findForUpdate(UUID customerId, String key);
    Optional<OrderRequest> findByOrderId(UUID orderId);
    void update(OrderRequest request);
}
