package io.polaris.inventory.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.polaris.inventory.domain.InventoryItem;
import io.polaris.inventory.persistence.InventoryItemRepository;
import io.polaris.shared.events.InventoryAdjustedEvent;

@Service
public class InventoryApplicationService {
    private final InventoryItemRepository inventoryItems;
    private final ApplicationEventPublisher events;

    public InventoryApplicationService(InventoryItemRepository inventoryItems, ApplicationEventPublisher events) {
        this.inventoryItems = inventoryItems;
        this.events = events;
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
            return new StockReservationResult(false, InventoryDecisionReason.INSUFFICIENT_STOCK, results);
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

        events.publishEvent(new InventoryAdjustedApplicationEvent(toInventoryAdjustedEvent(orderId, results)));
        return new StockReservationResult(true, InventoryDecisionReason.RESERVED, results);
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
        Map<String, Integer> requested = new LinkedHashMap<>();
        for (InventoryLine line : lines) {
            if (line.quantity() <= 0) {
                throw new IllegalArgumentException("quantity must be positive for SKU " + line.sku());
            }
            requested.merge(line.sku(), line.quantity(), Integer::sum);
        }
        return requested;
    }

    private InventoryAdjustedEvent toInventoryAdjustedEvent(UUID orderId, List<StockReservation> reservations) {
        List<InventoryAdjustedEvent.Item> items = reservations.stream()
                .map(reservation -> new InventoryAdjustedEvent.Item(
                        reservation.sku(),
                        -reservation.reservedQuantity(),
                        reservation.remainingQuantity()))
                .toList();
        return new InventoryAdjustedEvent(orderId, items, Instant.now());
    }
}
