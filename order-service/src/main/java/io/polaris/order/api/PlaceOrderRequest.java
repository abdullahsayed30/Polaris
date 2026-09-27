package io.polaris.order.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

public record PlaceOrderRequest(@NotEmpty @Valid List<OrderItemRequest> items) {
}
