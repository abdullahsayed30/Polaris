package io.polaris.order.inventory;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import io.grpc.StatusRuntimeException;

import io.polaris.inventory.grpc.InventoryServiceGrpc;
import io.polaris.inventory.grpc.ReserveRequest;
import io.polaris.inventory.grpc.ReserveResponse;
import io.polaris.inventory.grpc.StockItem;
import io.polaris.inventory.grpc.StockRequest;
import io.polaris.inventory.grpc.StockResponse;
import io.polaris.order.config.InventoryGrpcProperties;
import io.polaris.order.domain.Order;
import io.polaris.order.domain.OrderItem;

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
            return new StockCheckResult(response.getAvailable(), response.getReason());
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
            return new StockReservationResult(response.getReserved(), response.getReason());
        } catch (StatusRuntimeException ex) {
            throw new InventoryUnavailableException("Inventory service is unavailable", ex);
        }
    }
}
