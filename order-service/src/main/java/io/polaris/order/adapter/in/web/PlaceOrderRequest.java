package io.polaris.order.adapter.in.web;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

public record PlaceOrderRequest(@NotEmpty @Valid List<OrderItemRequest> items) {
}
