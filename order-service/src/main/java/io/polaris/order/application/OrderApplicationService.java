package io.polaris.order.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.polaris.order.domain.Order;
import io.polaris.order.persistence.OrderRepository;

@Service
public class OrderApplicationService {
    private final OrderRepository orderRepository;
    private final OrderPlacementTransactions placement;

    public OrderApplicationService(OrderRepository orderRepository, OrderPlacementTransactions placement) {
        this.orderRepository = orderRepository;
        this.placement = placement;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Order placeOrder(UUID customerId, List<PlaceOrderLine> lines) {
        return placeOrder(null, customerId, lines).order();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PlaceOrderResult placeOrder(String idempotencyKey, UUID customerId, List<PlaceOrderLine> lines) {
        // The intent must commit before inventory can change stock, even for headerless requests.
        PlaceOrderResult intent = placement.prepare(idempotencyKey, customerId, lines);
        return new PlaceOrderResult(placement.resolve(intent.order().getId()), intent.replayed());
    }

    @Transactional(readOnly = true)
    public Order getOrder(UUID orderId, UUID customerId) {
        return orderRepository.findWithItemsByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
