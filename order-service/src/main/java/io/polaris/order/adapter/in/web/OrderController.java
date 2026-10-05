package io.polaris.order.adapter.in.web;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.polaris.order.application.domain.model.Order;
import io.polaris.order.application.port.in.GetOrderUseCase;
import io.polaris.order.application.port.in.PlaceOrderLine;
import io.polaris.order.application.port.in.PlaceOrderResult;
import io.polaris.order.application.port.in.PlaceOrderUseCase;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed";

    private final PlaceOrderUseCase placeOrder;
    private final GetOrderUseCase getOrder;

    public OrderController(PlaceOrderUseCase placeOrder, GetOrderUseCase getOrder) {
        this.placeOrder = placeOrder;
        this.getOrder = getOrder;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request) {
        PlaceOrderResult result = placeOrder.placeOrder(
                idempotencyKey,
                UUID.fromString(jwt.getSubject()),
                request.items().stream()
                        .map(item -> new PlaceOrderLine(item.sku(), item.quantity(), item.unitPrice()))
                        .toList());
        Order order = result.order();
        return ResponseEntity
                .created(URI.create("/api/v1/orders/" + order.getId()))
                .header(IDEMPOTENCY_REPLAYED_HEADER, Boolean.toString(result.replayed()))
                .body(OrderResponse.from(order));
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return OrderResponse.from(getOrder.getOrder(id, UUID.fromString(jwt.getSubject())));
    }
}
