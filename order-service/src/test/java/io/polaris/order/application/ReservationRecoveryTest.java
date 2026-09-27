package io.polaris.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import io.polaris.order.config.ReservationRecoveryProperties;
import io.polaris.order.persistence.OrderRepository;

class ReservationRecoveryTest {
    @Test
    void failedReservationIsDeferredWithoutBlockingTheRestOfTheBatch() {
        OrderRepository orders = mock(OrderRepository.class);
        OrderPlacementTransactions placement = mock(OrderPlacementTransactions.class);
        UUID failing = UUID.randomUUID();
        UUID succeeding = UUID.randomUUID();
        when(orders.findPendingReservationIds(any(), any())).thenReturn(List.of(failing, succeeding));
        doThrow(new IllegalStateException("inventory unavailable")).when(placement).resolve(failing);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        ReservationRecovery recovery = new ReservationRecovery(orders, placement,
                new ReservationRecoveryProperties(50, Duration.ofSeconds(30)), metrics);

        recovery.recoverPending();

        verify(placement).defer(failing);
        verify(placement).resolve(succeeding);
        assertThat(metrics.counter("polaris.reservation.recovery", "outcome", "retry").count()).isEqualTo(1);
        assertThat(metrics.counter("polaris.reservation.recovery", "outcome", "resolved").count()).isEqualTo(1);
    }
}
