package io.polaris.order.application;

import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.polaris.order.domain.Order;
import io.polaris.order.domain.OrderItem;
import io.polaris.order.inventory.InventoryClient;
import io.polaris.order.persistence.OrderRepository;

@Service
public class OrderApplicationService {
    private final OrderRepository orderRepository;
    private final InventoryClient inventoryClient;
    private final ApplicationEventPublisher events;

    public OrderApplicationService(
            OrderRepository orderRepository,
            InventoryClient inventoryClient,
            ApplicationEventPublisher events) {
        this.orderRepository = orderRepository;
        this.inventoryClient = inventoryClient;
        this.events = events;
    }

    @Transactional
    public Order placeOrder(UUID customerId, List<PlaceOrderLine> lines) {
        List<OrderItem> items = lines.stream()
                .map(line -> OrderItem.create(line.sku(), line.quantity(), line.unitPrice()))
                .toList();

        Order order = Order.place(customerId, items);
        orderRepository.save(order);

        if (!inventoryClient.checkStock(order).available()) {
            order.cancel();
        } else if (inventoryClient.reserveStock(order).reserved()) {
            order.confirm();
        } else {
            order.cancel();
        }

        events.publishEvent(new OrderCreatedApplicationEvent(OrderEventMapper.toOrderCreatedEvent(order)));
        return order;
    }

    @Transactional(readOnly = true)
    public Order getOrder(UUID orderId) {
        return orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
