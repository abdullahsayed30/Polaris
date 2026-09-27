package io.polaris.order.application;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.MeterRegistry;

import io.polaris.order.config.ReservationRecoveryProperties;
import io.polaris.order.persistence.OrderRepository;

@Service
public class ReservationRecovery {
    private static final Logger log = LoggerFactory.getLogger(ReservationRecovery.class);
    private final OrderRepository orders;
    private final OrderPlacementTransactions placement;
    private final ReservationRecoveryProperties properties;
    private final MeterRegistry metrics;

    public ReservationRecovery(OrderRepository orders, OrderPlacementTransactions placement,
            ReservationRecoveryProperties properties, MeterRegistry metrics) {
        this.orders = orders;
        this.placement = placement;
        this.properties = properties;
        this.metrics = metrics;
    }

    public void recoverPending() {
        for (UUID id : orders.findPendingReservationIds(Instant.now(), PageRequest.of(0, properties.batchSize()))) {
            try {
                placement.resolve(id);
                metrics.counter("polaris.reservation.recovery", "outcome", "resolved").increment();
            } catch (RuntimeException ex) {
                metrics.counter("polaris.reservation.recovery", "outcome", "retry").increment();
                log.warn("Reservation recovery will retry orderId={}", id, ex);
                placement.defer(id);
            }
        }
    }
}
