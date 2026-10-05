package io.polaris.inventory.application.domain.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.polaris.inventory.application.domain.model.InventoryItem;
import io.polaris.inventory.application.domain.model.InventoryReservation;
import io.polaris.inventory.application.domain.model.InventoryReservationLine;
import io.polaris.inventory.application.domain.model.ReservationStatus;
import io.polaris.inventory.application.port.in.CheckStockUseCase;
import io.polaris.inventory.application.port.in.InventoryDecisionReason;
import io.polaris.inventory.application.port.in.InventoryLine;
import io.polaris.inventory.application.port.in.ReleaseStockUseCase;
import io.polaris.inventory.application.port.in.ReserveStockUseCase;
import io.polaris.inventory.application.port.in.StockAvailability;
import io.polaris.inventory.application.port.in.StockCheckResult;
import io.polaris.inventory.application.port.in.StockRelease;
import io.polaris.inventory.application.port.in.StockReleaseResult;
import io.polaris.inventory.application.port.in.StockReservation;
import io.polaris.inventory.application.port.in.StockReservationResult;
import io.polaris.inventory.application.port.out.EventContext;
import io.polaris.inventory.application.port.out.InventoryEventRecorder;
import io.polaris.inventory.application.port.out.InventoryStock;
import io.polaris.inventory.application.port.out.ReservationStore;
import io.polaris.shared.events.EventMetadata;
import io.polaris.shared.events.InventoryAdjustedEvent;

@Service
public class InventoryApplicationService implements CheckStockUseCase, ReserveStockUseCase, ReleaseStockUseCase {
    private final InventoryStock inventoryItems;
    private final ReservationStore reservations;
    private final InventoryEventRecorder eventOutbox;
    private final EventContext eventContext;

    public InventoryApplicationService(
            InventoryStock inventoryItems,
            ReservationStore reservations,
            InventoryEventRecorder eventOutbox, EventContext eventContext) {
        this.inventoryItems = inventoryItems;
        this.reservations = reservations;
        this.eventOutbox = eventOutbox;
        this.eventContext = eventContext;
    }

    @Transactional(readOnly = true)
    public StockCheckResult checkStock(UUID orderId, List<InventoryLine> lines) {
        Map<String, Integer> requested = requestedQuantities(lines);
        Map<String, InventoryItem> currentStock = inventoryItems.findBySkuIn(requested.keySet()).stream()
                .collect(Collectors.toMap(InventoryItem::getSku, Function.identity()));

        List<StockAvailability> results = requested.entrySet().stream()
                .map(entry -> availability(entry.getKey(), entry.getValue(), currentStock.get(entry.getKey())))
                .toList();

        boolean available = results.stream().allMatch(StockAvailability::available);
        return new StockCheckResult(
                available,
                available ? InventoryDecisionReason.AVAILABLE : InventoryDecisionReason.INSUFFICIENT_STOCK,
                results);
    }

    @Transactional
    public StockReservationResult reserveStock(UUID orderId, List<InventoryLine> lines) {
        Map<String, Integer> requested = requestedQuantities(lines);
        int inserted = reservations.insertIfAbsent(orderId);
        InventoryReservation reservation = reservations.findForUpdate(orderId)
                .orElseThrow(() -> new IllegalStateException("Reservation row disappeared for order " + orderId));

        if (inserted == 0) {
            return replayReservation(reservation, requested);
        }

        Map<String, InventoryItem> currentStock = inventoryItems.findBySkuInForUpdate(requested.keySet()).stream()
                .collect(Collectors.toMap(InventoryItem::getSku, Function.identity()));

        List<StockReservation> preview = requested.entrySet().stream()
                .map(entry -> reservationPreview(entry.getKey(), entry.getValue(), currentStock.get(entry.getKey())))
                .toList();

        if (preview.stream().anyMatch(result -> !result.reserved())) {
            List<StockReservation> results = preview.stream()
                    .map(result -> new StockReservation(
                            result.sku(),
                            result.requestedQuantity(),
                            0,
                            result.remainingQuantity(),
                            false))
                    .toList();
            reservation.recordDecision(ReservationStatus.REJECTED, toReservationLines(results));
            reservations.update(reservation);
            return new StockReservationResult(
                    false,
                    InventoryDecisionReason.INSUFFICIENT_STOCK,
                    ReservationStatus.REJECTED,
                    results);
        }

        List<StockReservation> results = requested.entrySet().stream()
                .map(entry -> {
                    InventoryItem item = currentStock.get(entry.getKey());
                    item.reserve(entry.getValue());
                    return new StockReservation(
                            entry.getKey(),
                            entry.getValue(),
                            entry.getValue(),
                            item.getAvailableQuantity(),
                            true);
                })
                .toList();

        reservation.recordDecision(ReservationStatus.RESERVED, toReservationLines(results));
        inventoryItems.update(currentStock.values());
        reservations.update(reservation);
        eventOutbox.enqueue(toInventoryAdjustedEvent(orderId, results));
        return new StockReservationResult(
                true,
                InventoryDecisionReason.RESERVED,
                ReservationStatus.RESERVED,
                results);
    }

    @Transactional
    public StockReleaseResult releaseStock(UUID orderId) {
        InventoryReservation reservation = reservations.findForUpdate(orderId)
                .orElseThrow(() -> new IllegalStateException("No reservation exists for order " + orderId));

        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            return storedRelease(reservation);
        }
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw new IllegalStateException(
                    "Reservation for order " + orderId + " is " + reservation.getStatus() + " and cannot be released");
        }

        Map<String, InventoryItem> currentStock = inventoryItems.findBySkuInForUpdate(reservation.getLines().stream()
                .map(InventoryReservationLine::getSku)
                .toList())
                .stream()
                .collect(Collectors.toMap(InventoryItem::getSku, Function.identity()));

        List<StockRelease> results = reservation.getLines().stream()
                .map(line -> {
                    InventoryItem item = currentStock.get(line.getSku());
                    if (item == null) {
                        throw new IllegalStateException("Inventory item disappeared for reserved SKU " + line.getSku());
                    }
                    item.release(line.getReservedQuantity());
                    line.recordRelease(item.getAvailableQuantity());
                    return new StockRelease(
                            line.getSku(),
                            line.getReservedQuantity(),
                            item.getAvailableQuantity());
                })
                .toList();

        reservation.release();
        inventoryItems.update(currentStock.values());
        reservations.update(reservation);
        eventOutbox.enqueue(toReleaseAdjustedEvent(orderId, results));
        return new StockReleaseResult(
                true,
                InventoryDecisionReason.RELEASED,
                ReservationStatus.RELEASED,
                results);
    }

    private StockAvailability availability(String sku, int requestedQuantity, InventoryItem item) {
        int availableQuantity = item == null ? 0 : item.getAvailableQuantity();
        return new StockAvailability(sku, requestedQuantity, availableQuantity, availableQuantity >= requestedQuantity);
    }

    private StockReservation reservationPreview(String sku, int requestedQuantity, InventoryItem item) {
        int availableQuantity = item == null ? 0 : item.getAvailableQuantity();
        boolean reserved = item != null && availableQuantity >= requestedQuantity;
        return new StockReservation(sku, requestedQuantity, 0, availableQuantity, reserved);
    }

    private Map<String, Integer> requestedQuantities(List<InventoryLine> lines) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("at least one inventory line is required");
        }
        Map<String, Integer> requested = new TreeMap<>();
        for (InventoryLine line : lines) {
            if (line.sku() == null || line.sku().isBlank()) {
                throw new IllegalArgumentException("sku must not be blank");
            }
            if (line.quantity() <= 0) {
                throw new IllegalArgumentException("quantity must be positive for SKU " + line.sku());
            }
            try {
                requested.merge(line.sku(), line.quantity(), Math::addExact);
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException("requested quantity is too large for SKU " + line.sku(), ex);
            }
        }
        return requested;
    }

    private StockReservationResult replayReservation(
            InventoryReservation reservation,
            Map<String, Integer> requested) {
        Map<String, Integer> storedRequest = reservation.getLines().stream()
                .collect(Collectors.toMap(
                        InventoryReservationLine::getSku,
                        InventoryReservationLine::getRequestedQuantity,
                        (first, second) -> first,
                        TreeMap::new));
        if (!storedRequest.equals(requested)) {
            throw new IllegalStateException(
                    "Order " + reservation.getOrderId() + " already has a reservation with different items");
        }

        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            throw new IllegalStateException(
                    "Reservation for order " + reservation.getOrderId() + " has already been released");
        }
        if (reservation.getStatus() == ReservationStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Reservation for order " + reservation.getOrderId() + " has no completed decision");
        }

        List<StockReservation> results = reservation.getLines().stream()
                .map(this::toStockReservation)
                .toList();
        boolean reserved = reservation.getStatus() == ReservationStatus.RESERVED;
        return new StockReservationResult(
                reserved,
                reserved ? InventoryDecisionReason.RESERVED : InventoryDecisionReason.INSUFFICIENT_STOCK,
                reservation.getStatus(),
                results);
    }

    private List<InventoryReservationLine> toReservationLines(List<StockReservation> results) {
        return results.stream()
                .map(result -> InventoryReservationLine.create(
                        result.sku(),
                        result.requestedQuantity(),
                        result.reservedQuantity(),
                        result.remainingQuantity()))
                .toList();
    }

    private StockReservation toStockReservation(InventoryReservationLine line) {
        return new StockReservation(
                line.getSku(),
                line.getRequestedQuantity(),
                line.getReservedQuantity(),
                line.getRemainingQuantity(),
                line.getReservedQuantity() == line.getRequestedQuantity());
    }

    private StockReleaseResult storedRelease(InventoryReservation reservation) {
        List<StockRelease> results = reservation.getLines().stream()
                .map(line -> {
                    if (line.getReleasedAvailableQuantity() == null) {
                        throw new IllegalStateException("Released reservation is missing its release result");
                    }
                    return new StockRelease(
                            line.getSku(),
                            line.getReservedQuantity(),
                            line.getReleasedAvailableQuantity());
                })
                .toList();
        return new StockReleaseResult(
                true,
                InventoryDecisionReason.RELEASED,
                ReservationStatus.RELEASED,
                results);
    }

    private InventoryAdjustedEvent toInventoryAdjustedEvent(UUID orderId, List<StockReservation> reservations) {
        Instant occurredAt = Instant.now();
        List<InventoryAdjustedEvent.Item> items = reservations.stream()
                .map(reservation -> new InventoryAdjustedEvent.Item(
                        reservation.sku(),
                        -reservation.reservedQuantity(),
                        reservation.remainingQuantity()))
                .toList();
        return new InventoryAdjustedEvent(metadata(occurredAt), orderId, items, occurredAt);
    }

    private InventoryAdjustedEvent toReleaseAdjustedEvent(UUID orderId, List<StockRelease> releases) {
        Instant occurredAt = Instant.now();
        List<InventoryAdjustedEvent.Item> items = releases.stream()
                .map(release -> new InventoryAdjustedEvent.Item(
                        release.sku(),
                        release.releasedQuantity(),
                        release.availableQuantity()))
                .toList();
        return new InventoryAdjustedEvent(metadata(occurredAt), orderId, items, occurredAt);
    }

    private EventMetadata metadata(Instant occurredAt) {
        return EventMetadata.initial(
                InventoryAdjustedEvent.EVENT_VERSION,
                occurredAt,
                eventContext.correlationId());
    }
}
