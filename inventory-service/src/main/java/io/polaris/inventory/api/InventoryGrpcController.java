package io.polaris.inventory.api;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.polaris.inventory.application.InventoryApplicationService;
import io.polaris.inventory.application.InventoryDecisionReason;
import io.polaris.inventory.application.InventoryLine;
import io.polaris.inventory.application.StockAvailability;
import io.polaris.inventory.application.StockCheckResult;
import io.polaris.inventory.application.StockReservation;
import io.polaris.inventory.application.StockReservationResult;
import io.polaris.inventory.grpc.InventoryDecision;
import io.polaris.inventory.grpc.InventoryServiceGrpc;
import io.polaris.inventory.grpc.ReserveRequest;
import io.polaris.inventory.grpc.ReserveResponse;
import io.polaris.inventory.grpc.StockItemAvailability;
import io.polaris.inventory.grpc.StockItemReservation;
import io.polaris.inventory.grpc.StockRequest;
import io.polaris.inventory.grpc.StockResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class InventoryGrpcController extends InventoryServiceGrpc.InventoryServiceImplBase {
    private final InventoryApplicationService inventory;

    public InventoryGrpcController(InventoryApplicationService inventory) {
        this.inventory = inventory;
    }

    @Override
    public void checkStock(StockRequest request, StreamObserver<StockResponse> responseObserver) {
        try {
            StockCheckResult result = inventory.checkStock(orderId(request.getOrderId()), lines(request.getItemsList()));
            responseObserver.onNext(toResponse(result));
            responseObserver.onCompleted();
        } catch (IllegalArgumentException ex) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(ex.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void reserveStock(ReserveRequest request, StreamObserver<ReserveResponse> responseObserver) {
        try {
            StockReservationResult result = inventory.reserveStock(orderId(request.getOrderId()), lines(request.getItemsList()));
            responseObserver.onNext(toResponse(result));
            responseObserver.onCompleted();
        } catch (IllegalArgumentException ex) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(ex.getMessage()).asRuntimeException());
        } catch (IllegalStateException ex) {
            responseObserver.onError(Status.FAILED_PRECONDITION.withDescription(ex.getMessage()).asRuntimeException());
        }
    }

    private UUID orderId(String orderId) {
        return UUID.fromString(orderId);
    }

    private List<InventoryLine> lines(List<io.polaris.inventory.grpc.StockItem> items) {
        return items.stream()
                .map(item -> new InventoryLine(item.getSku(), item.getQuantity()))
                .toList();
    }

    private StockResponse toResponse(StockCheckResult result) {
        StockResponse.Builder response = StockResponse.newBuilder()
                .setAvailable(result.available())
                .setReason(toResponse(result.reason()));
        result.items().forEach(item -> response.addItems(toResponse(item)));
        return response.build();
    }

    private StockItemAvailability toResponse(StockAvailability item) {
        return StockItemAvailability.newBuilder()
                .setSku(item.sku())
                .setRequestedQuantity(item.requestedQuantity())
                .setAvailableQuantity(item.availableQuantity())
                .setAvailable(item.available())
                .build();
    }

    private ReserveResponse toResponse(StockReservationResult result) {
        ReserveResponse.Builder response = ReserveResponse.newBuilder()
                .setReserved(result.reserved())
                .setReason(toResponse(result.reason()));
        result.items().forEach(item -> response.addItems(toResponse(item)));
        return response.build();
    }

    private InventoryDecision toResponse(InventoryDecisionReason reason) {
        return switch (reason) {
            case AVAILABLE -> InventoryDecision.INVENTORY_DECISION_AVAILABLE;
            case RESERVED -> InventoryDecision.INVENTORY_DECISION_RESERVED;
            case INSUFFICIENT_STOCK -> InventoryDecision.INVENTORY_DECISION_INSUFFICIENT_STOCK;
        };
    }

    private StockItemReservation toResponse(StockReservation item) {
        return StockItemReservation.newBuilder()
                .setSku(item.sku())
                .setRequestedQuantity(item.requestedQuantity())
                .setReservedQuantity(item.reservedQuantity())
                .setRemainingQuantity(item.remainingQuantity())
                .setReserved(item.reserved())
                .build();
    }
}
