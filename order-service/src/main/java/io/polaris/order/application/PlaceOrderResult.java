package io.polaris.order.application;

import io.polaris.order.domain.Order;

public record PlaceOrderResult(Order order, boolean replayed) {
}
