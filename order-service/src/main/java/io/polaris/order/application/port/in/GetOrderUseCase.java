package io.polaris.order.application.port.in;

import java.util.UUID;

import io.polaris.order.application.domain.model.Order;

public interface GetOrderUseCase {
    Order getOrder(UUID orderId, UUID customerId);
}
