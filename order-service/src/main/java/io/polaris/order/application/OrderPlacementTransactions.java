package io.polaris.order.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.polaris.order.config.ReservationRecoveryProperties;
import io.polaris.order.domain.Order;
import io.polaris.order.domain.OrderItem;
import io.polaris.order.domain.OrderRequest;
import io.polaris.order.domain.OrderStatus;
import io.polaris.order.inventory.InventoryClient;
import io.polaris.order.persistence.OrderRepository;
import io.polaris.order.persistence.OrderRequestRepository;

@Service
public class OrderPlacementTransactions {
    private final OrderRepository orders;
    private final OrderRequestRepository requests;
    private final InventoryClient inventory;
    private final OrderEventRecorder events;
    private final ReservationRecoveryProperties properties;

    public OrderPlacementTransactions(
            OrderRepository orders,
            OrderRequestRepository requests,
            InventoryClient inventory,
            OrderEventRecorder events,
            ReservationRecoveryProperties properties) {
        this.orders = orders;
        this.requests = requests;
        this.inventory = inventory;
        this.events = events;
        this.properties = properties;
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
        Order order = orders.saveAndFlush(Order.place(orderId, customerId, items));
        orders.deferReservation(orderId, Instant.now().plus(properties.retryDelay()));
        if (request != null) {
            request.bindOrder(orderId);
        }
        return new PlaceOrderResult(order, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Order resolve(UUID orderId) {
        // This local lock serializes HTTP retries and workers; the gRPC call has a bounded deadline.
        Order order = orders.findForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        order.getItems().size();
        if (order.getStatus() != OrderStatus.PENDING) {
            return order;
        }
        if (inventory.reserveStock(order).reserved()) {
            order.confirm();
        } else {
            order.cancel();
        }
        events.enqueue(OrderEventMapper.toOrderCreatedEvent(order));
        requests.findByOrderId(orderId).ifPresent(request -> request.complete(orderId));
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
