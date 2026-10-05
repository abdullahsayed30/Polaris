package io.polaris.order.adapter.out.grpc;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import io.grpc.StatusRuntimeException;

import io.polaris.inventory.grpc.InventoryServiceGrpc;
import io.polaris.inventory.grpc.ReserveRequest;
import io.polaris.inventory.grpc.ReserveResponse;
import io.polaris.inventory.grpc.StockItem;
import io.polaris.inventory.grpc.StockRequest;
import io.polaris.inventory.grpc.StockResponse;
import io.polaris.order.InventoryGrpcProperties;
import io.polaris.order.application.domain.model.Order;
import io.polaris.order.application.domain.model.OrderItem;
import io.polaris.order.application.port.out.InventoryClient;
import io.polaris.order.application.port.out.InventoryUnavailableException;
import io.polaris.order.application.port.out.StockCheckResult;
import io.polaris.order.application.port.out.StockReservationResult;

@Component
public class GrpcInventoryClient implements InventoryClient {
    private final InventoryServiceGrpc.InventoryServiceBlockingStub inventoryService;
    private final InventoryGrpcProperties properties;

    public GrpcInventoryClient(
            InventoryServiceGrpc.InventoryServiceBlockingStub inventoryService,
            InventoryGrpcProperties properties) {
        this.inventoryService = inventoryService;
        this.properties = properties;
    }

    private io.polaris.order.application.port.out.InventoryDecision decision(io.polaris.inventory.grpc.InventoryDecision reason) {
        return switch (reason) {
            case INVENTORY_DECISION_AVAILABLE -> io.polaris.order.application.port.out.InventoryDecision.AVAILABLE;
            case INVENTORY_DECISION_RESERVED -> io.polaris.order.application.port.out.InventoryDecision.RESERVED;
            case INVENTORY_DECISION_INSUFFICIENT_STOCK -> io.polaris.order.application.port.out.InventoryDecision.INSUFFICIENT_STOCK;
            case INVENTORY_DECISION_RELEASED -> io.polaris.order.application.port.out.InventoryDecision.RELEASED;
            default -> io.polaris.order.application.port.out.InventoryDecision.UNSPECIFIED;
        };
    }
    @Override
    public StockCheckResult checkStock(Order order) {
        StockRequest.Builder request = StockRequest.newBuilder()
                .setOrderId(order.getId().toString());

        for (OrderItem item : order.getItems()) {
            request.addItems(StockItem.newBuilder()
                    .setSku(item.getSku())
                    .setQuantity(item.getQuantity())
                    .build());
        }

        try {
            StockResponse response = inventoryService
                    .withDeadlineAfter(properties.deadline().toMillis(), TimeUnit.MILLISECONDS)
                    .checkStock(request.build());
            return new StockCheckResult(response.getAvailable(), decision(response.getReason()));
        } catch (StatusRuntimeException ex) {
            throw new InventoryUnavailableException("Inventory service is unavailable", ex);
        }
    }

    @Override
    public StockReservationResult reserveStock(Order order) {
        ReserveRequest.Builder request = ReserveRequest.newBuilder()
                .setOrderId(order.getId().toString());

        for (OrderItem item : order.getItems()) {
            request.addItems(StockItem.newBuilder()
                    .setSku(item.getSku())
                    .setQuantity(item.getQuantity())
                    .build());
        }

        try {
            ReserveResponse response = inventoryService
                    .withDeadlineAfter(properties.deadline().toMillis(), TimeUnit.MILLISECONDS)
                    .reserveStock(request.build());
            return new StockReservationResult(response.getReserved(), decision(response.getReason()));
        } catch (StatusRuntimeException ex) {
            throw new InventoryUnavailableException("Inventory service is unavailable", ex);
        }
    }
}
