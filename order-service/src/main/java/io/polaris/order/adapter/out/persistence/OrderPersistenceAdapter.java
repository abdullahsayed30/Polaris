package io.polaris.order.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.polaris.order.adapter.out.observability.DurableTelemetry;
import io.polaris.order.application.domain.model.Order;
import io.polaris.order.application.port.out.OrderStore;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class OrderPersistenceAdapter implements OrderStore {
    private final OrderRepository orders;
    private final DurableTelemetry telemetry;
    public OrderPersistenceAdapter(OrderRepository orders, DurableTelemetry telemetry) {
        this.telemetry = telemetry;
        this.orders = orders;
    }
    public Optional<Order> findWithItemsByIdAndCustomerId(UUID id, UUID customerId) {
        return telemetry.database("SELECT", "orders",
                () -> orders.findWithItemsByIdAndCustomerId(id, customerId).map(OrderMapper::toDomain));
    }
    public Optional<Order> findForUpdate(UUID id) {
        var session = telemetry.resume("order.resolve.attempt",
                telemetry.database("SELECT", "orders", () -> orders.findRecoveryTraceContext(id).orElse(null)), id.toString());
        session.closeWithTransaction();
        return telemetry.database("SELECT", "orders", () -> orders.findForUpdate(id).map(OrderMapper::toDomain));
    }
    public Order create(Order order) {
        try (var session = telemetry.child("order.intent.create", order.getId().toString())) {
            var entity = OrderMapper.toEntity(order);
            entity.recoveryTraceContext = telemetry.capture();
            return telemetry.database("INSERT", "orders", () -> OrderMapper.toDomain(orders.saveAndFlush(entity)));
        }
    }
    public Order update(Order order) {
        return telemetry.database("UPDATE", "orders", () -> {
            var managed = orders.findById(order.getId()).orElseThrow();
            OrderMapper.update(order, managed);
            orders.flush();
            return OrderMapper.toDomain(managed);
        });
    }
    // The recovery scanner selects candidates only; resolve acquires each lock in a new transaction.
    @Transactional(readOnly = true)
    public List<UUID> findPendingReservationIds(Instant now, int limit) {
        return telemetry.database("SELECT", "orders", () -> orders.findPendingReservationIds(now, PageRequest.of(0, limit)));
    }
    public void deferReservation(UUID id, Instant retryAt) {
        telemetry.databaseWrite("UPDATE", "orders", () -> orders.deferReservation(id, retryAt));
    }
}
