package io.polaris.order.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.polaris.order.application.domain.model.Order;

/** Storage operations join the caller transaction; locks remain held until it completes. */
public interface OrderStore {
    Optional<Order> findWithItemsByIdAndCustomerId(UUID id, UUID customerId);
    Optional<Order> findForUpdate(UUID id);
    Order create(Order order);
    Order update(Order order);
    List<UUID> findPendingReservationIds(Instant now, int limit);
    void deferReservation(UUID id, Instant retryAt);
}
