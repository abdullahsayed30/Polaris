package io.polaris.order.application.domain.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.polaris.order.application.domain.model.Order;
import io.polaris.order.application.domain.model.OrderItem;
import io.polaris.order.application.domain.model.OrderRequest;
import io.polaris.order.application.domain.model.OrderStatus;
import io.polaris.order.application.port.in.IdempotencyConflictException;
import io.polaris.order.application.port.in.InvalidIdempotencyKeyException;
import io.polaris.order.application.port.in.OrderNotFoundException;
import io.polaris.order.application.port.in.PlaceOrderLine;
import io.polaris.order.application.port.in.PlaceOrderResult;
import io.polaris.order.application.port.out.EventContext;
import io.polaris.order.application.port.out.InventoryClient;
import io.polaris.order.application.port.out.OrderEventRecorder;
import io.polaris.order.application.port.out.OrderRequestStore;
import io.polaris.order.application.port.out.OrderStore;

@Service
public class OrderPlacementTransactions {
    private final OrderStore orders;
    private final OrderRequestStore requests;
    private final InventoryClient inventory;
    private final OrderEventRecorder events;
    private final ReservationRecoveryPolicy properties;
    private final EventContext eventContext;

    public OrderPlacementTransactions(
            OrderStore orders,
            OrderRequestStore requests,
            InventoryClient inventory,
            OrderEventRecorder events,
            ReservationRecoveryPolicy properties, EventContext eventContext) {
        this.orders = orders;
        this.requests = requests;
        this.inventory = inventory;
        this.events = events;
        this.properties = properties;
        this.eventContext = eventContext;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PlaceOrderResult prepare(String key, UUID customerId, List<PlaceOrderLine> lines) {
        OrderRequest request = null;
        UUID orderId = UUID.randomUUID();
        if (key != null) {
            validateKey(key);
            String hash = OrderRequestFingerprint.create(customerId, lines);
            int inserted = requests.insertIfAbsent(deterministicId("request", customerId, key), customerId, key, hash);
            request = requests.findForUpdate(customerId, key)
                    .orElseThrow(() -> new IllegalStateException("Order request row disappeared"));
            if (!request.getRequestHash().equals(hash)) {
                throw new IdempotencyConflictException(key);
            }
            if (inserted == 0) {
                Order existing = orders.findWithItemsByIdAndCustomerId(request.getOrderId(), customerId)
                        .orElseThrow(() -> new IllegalStateException("Order intent row disappeared"));
                return new PlaceOrderResult(existing, true);
            }
            orderId = deterministicId("order", customerId, key);
        }
        List<OrderItem> items = lines.stream()
                .map(line -> OrderItem.create(line.sku(), line.quantity(), line.unitPrice()))
                .toList();
        Order order = orders.create(Order.place(orderId, customerId, items));
        orders.deferReservation(orderId, Instant.now().plus(properties.retryDelay()));
        if (request != null) {
            request.bindOrder(orderId);
            requests.update(request);
        }
        return new PlaceOrderResult(order, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Order resolve(UUID orderId) {
        // This local lock serializes HTTP retries and workers; the gRPC call has a bounded deadline.
        Order order = orders.findForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() != OrderStatus.PENDING) {
            return order;
        }
        if (inventory.reserveStock(order).reserved()) {
            order.confirm();
        } else {
            order.cancel();
        }
        order = orders.update(order);
        events.enqueue(OrderEventMapper.toOrderCreatedEvent(order, eventContext.correlationId()));
        requests.findByOrderId(orderId).ifPresent(request -> {
            request.complete(orderId);
            requests.update(request);
        });
        return order;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void defer(UUID orderId) {
        orders.deferReservation(orderId, Instant.now().plus(properties.retryDelay()));
    }

    private void validateKey(String key) {
        if (key.isBlank()) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key must not be blank");
        }
        if (key.length() > 128) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key must not exceed 128 characters");
        }
    }

    private UUID deterministicId(String purpose, UUID customerId, String key) {
        String source = "polaris:" + purpose + ":" + customerId + ":" + key;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }
}
