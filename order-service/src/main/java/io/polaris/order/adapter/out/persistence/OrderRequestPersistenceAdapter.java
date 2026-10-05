package io.polaris.order.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.polaris.order.application.domain.model.OrderRequest;
import io.polaris.order.application.port.out.OrderRequestStore;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class OrderRequestPersistenceAdapter implements OrderRequestStore {
    private final OrderRequestRepository requests;
    public OrderRequestPersistenceAdapter(OrderRequestRepository requests) {
        this.requests = requests;
    }
    public int insertIfAbsent(UUID id, UUID customerId, String key, String hash) {
        return requests.insertIfAbsent(id, customerId, key, hash);
    }
    public Optional<OrderRequest> findForUpdate(UUID customerId, String key) {
        return requests.findForUpdate(customerId, key).map(OrderRequestMapper::toDomain);
    }
    public Optional<OrderRequest> findByOrderId(UUID orderId) {
        return requests.findByOrderId(orderId).map(OrderRequestMapper::toDomain);
    }
    public void update(OrderRequest request) {
        var managed = requests.findById(request.getId()).orElseThrow();
        OrderRequestMapper.update(request, managed);
        requests.flush();
    }
}
