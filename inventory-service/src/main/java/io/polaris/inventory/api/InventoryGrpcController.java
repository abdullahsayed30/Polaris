package io.polaris.inventory.api;

import java.util.List;
import java.util.UUID;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import io.polaris.inventory.application.InventoryApplicationService;
import io.polaris.inventory.application.InventoryDecisionReason;
import io.polaris.inventory.application.InventoryLine;
import io.polaris.inventory.application.StockAvailability;
import io.polaris.inventory.application.StockCheckResult;
import io.polaris.inventory.application.StockRelease;
import io.polaris.inventory.application.StockReleaseResult;
import io.polaris.inventory.application.StockReservation;
import io.polaris.inventory.application.StockReservationResult;
import io.polaris.inventory.domain.ReservationStatus;
import io.polaris.inventory.grpc.InventoryDecision;
import io.polaris.inventory.grpc.InventoryServiceGrpc;
import io.polaris.inventory.grpc.ReleaseRequest;
import io.polaris.inventory.grpc.ReleaseResponse;
import io.polaris.inventory.grpc.ReservationState;
import io.polaris.inventory.grpc.ReserveRequest;
import io.polaris.inventory.grpc.ReserveResponse;
import io.polaris.inventory.grpc.StockItemAvailability;
import io.polaris.inventory.grpc.StockItemRelease;
import io.polaris.inventory.grpc.StockItemReservation;
import io.polaris.inventory.grpc.StockRequest;
import io.polaris.inventory.grpc.StockResponse;

import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
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

    @Override
    public void releaseStock(ReleaseRequest request, StreamObserver<ReleaseResponse> responseObserver) {
        try {
            StockReleaseResult result = inventory.releaseStock(orderId(request.getOrderId()));
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
                .setReason(toResponse(result.reason()))
                .setState(toResponse(result.status()));
        result.items().forEach(item -> response.addItems(toResponse(item)));
        return response.build();
    }

    private InventoryDecision toResponse(InventoryDecisionReason reason) {
        return switch (reason) {
            case AVAILABLE -> InventoryDecision.INVENTORY_DECISION_AVAILABLE;
            case RESERVED -> InventoryDecision.INVENTORY_DECISION_RESERVED;
            case INSUFFICIENT_STOCK -> InventoryDecision.INVENTORY_DECISION_INSUFFICIENT_STOCK;
            case RELEASED -> InventoryDecision.INVENTORY_DECISION_RELEASED;
        };
    }

    private ReservationState toResponse(ReservationStatus status) {
        return switch (status) {
            case PROCESSING -> ReservationState.RESERVATION_STATE_UNSPECIFIED;
            case RESERVED -> ReservationState.RESERVATION_STATE_RESERVED;
            case REJECTED -> ReservationState.RESERVATION_STATE_REJECTED;
            case RELEASED -> ReservationState.RESERVATION_STATE_RELEASED;
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

    private ReleaseResponse toResponse(StockReleaseResult result) {
        ReleaseResponse.Builder response = ReleaseResponse.newBuilder()
                .setReleased(result.released())
                .setReason(toResponse(result.reason()))
                .setState(toResponse(result.status()));
        result.items().forEach(item -> response.addItems(toResponse(item)));
        return response.build();
    }

    private StockItemRelease toResponse(StockRelease item) {
        return StockItemRelease.newBuilder()
                .setSku(item.sku())
                .setReleasedQuantity(item.releasedQuantity())
                .setAvailableQuantity(item.availableQuantity())
                .build();
    }
}
