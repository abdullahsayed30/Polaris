package io.polaris.order.application.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class Order {

    private UUID id;

    private UUID customerId;

    private OrderStatus status;

    private List<OrderItem> items = new ArrayList<>();

    private Instant createdAt;

    private Instant updatedAt;

    private long version;

    private Order() {
    }

    private Order(UUID id, UUID customerId) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.customerId = Objects.requireNonNull(customerId, "customerId must not be null");
        this.status = OrderStatus.PENDING;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Order place(UUID customerId, List<OrderItem> items) {
        return place(UUID.randomUUID(), customerId, items);
    }

    public static Order place(UUID orderId, UUID customerId, List<OrderItem> items) {
        Order order = new Order(orderId, customerId);
        items.forEach(order::addItem);
        return order;
    }

    public void confirm() {
        this.status = OrderStatus.CONFIRMED;
    }

    public void cancel() {
        this.status = OrderStatus.CANCELLED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private void addItem(OrderItem item) {
        items.add(item);
    }

    public long getVersion() {
        return version;
    }

    /** Rehydrates a stored snapshot without replaying a business transition. */
    public static Order restore(UUID id, UUID customerId, OrderStatus status, List<OrderItem> items, Instant createdAt, Instant updatedAt,
            long version) {
        Order result = new Order();
        result.id = id;
        result.customerId = customerId;
        result.status = status;
        result.items = new ArrayList<>(items);
        result.createdAt = createdAt;
        result.updatedAt = updatedAt;
        result.version = version;
        return result;
    }
}
