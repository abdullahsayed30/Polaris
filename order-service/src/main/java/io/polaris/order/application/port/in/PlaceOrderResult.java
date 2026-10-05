package io.polaris.order.application.port.in;

import io.polaris.order.application.domain.model.Order;

public record PlaceOrderResult(Order order, boolean replayed) {
}
