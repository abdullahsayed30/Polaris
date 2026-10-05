package io.polaris.order.application.domain.service;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.polaris.order.application.port.in.RecoverPendingOrdersUseCase;
import io.polaris.order.application.port.out.OrderStore;
import io.polaris.order.application.port.out.RecoveryMetrics;

@Service
public class ReservationRecovery implements RecoverPendingOrdersUseCase {
    private static final Logger log = LoggerFactory.getLogger(ReservationRecovery.class);
    private final OrderStore orders;
    private final OrderPlacementTransactions placement;
    private final ReservationRecoveryPolicy properties;
    private final RecoveryMetrics metrics;

    public ReservationRecovery(OrderStore orders, OrderPlacementTransactions placement,
            ReservationRecoveryPolicy properties, RecoveryMetrics metrics) {
        this.orders = orders;
        this.placement = placement;
        this.properties = properties;
        this.metrics = metrics;
    }

    public void recoverPending() {
        for (UUID id : orders.findPendingReservationIds(Instant.now(), properties.batchSize())) {
            try {
                placement.resolve(id);
                metrics.resolved();
            } catch (RuntimeException ex) {
                metrics.retry();
                log.warn("Reservation recovery will retry orderId={}", id, ex);
                placement.defer(id);
            }
        }
    }
}
