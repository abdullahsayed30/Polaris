package io.polaris.order.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.polaris.order.application.domain.model.Order;
import io.polaris.order.application.port.out.OrderStore;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class OrderPersistenceAdapter implements OrderStore {
    private final OrderRepository orders;
    public OrderPersistenceAdapter(OrderRepository orders) {
        this.orders = orders;
    }
    public Optional<Order> findWithItemsByIdAndCustomerId(UUID id, UUID customerId) {
        return orders.findWithItemsByIdAndCustomerId(id, customerId).map(OrderMapper::toDomain);
    }
    public Optional<Order> findForUpdate(UUID id) {
        return orders.findForUpdate(id).map(OrderMapper::toDomain);
    }
    public Order create(Order order) {
        return OrderMapper.toDomain(orders.saveAndFlush(OrderMapper.toEntity(order)));
    }
    public Order update(Order order) {
        var managed = orders.findById(order.getId()).orElseThrow();
        OrderMapper.update(order, managed);
        orders.flush();
        return OrderMapper.toDomain(managed);
    }
    // The recovery scanner selects candidates only; resolve acquires each lock in a new transaction.
    @Transactional(readOnly = true)
    public List<UUID> findPendingReservationIds(Instant now, int limit) {
        return orders.findPendingReservationIds(now, PageRequest.of(0, limit));
    }
    public void deferReservation(UUID id, Instant retryAt) {
        orders.deferReservation(id, retryAt);
    }
}
