package io.polaris.order.application.port.in;

import java.util.List;
import java.util.UUID;

import io.polaris.order.application.domain.model.Order;

public interface PlaceOrderUseCase {
    Order placeOrder(UUID customerId, List<PlaceOrderLine> lines);
    PlaceOrderResult placeOrder(String key, UUID customerId, List<PlaceOrderLine> lines);
}
