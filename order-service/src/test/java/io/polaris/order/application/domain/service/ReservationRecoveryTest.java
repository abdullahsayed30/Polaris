package io.polaris.order.application.domain.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.polaris.order.application.port.out.OrderStore;
import io.polaris.order.application.port.out.RecoveryMetrics;

class ReservationRecoveryTest {
    @Test
    void failedReservationIsDeferredWithoutBlockingTheRestOfTheBatch() {
        OrderStore orders = mock(OrderStore.class);
        OrderPlacementTransactions placement = mock(OrderPlacementTransactions.class);
        UUID failing = UUID.randomUUID();
        UUID succeeding = UUID.randomUUID();
        when(orders.findPendingReservationIds(any(), eq(50))).thenReturn(List.of(failing, succeeding));
        doThrow(new IllegalStateException("inventory unavailable")).when(placement).resolve(failing);
        RecoveryMetrics metrics = mock(RecoveryMetrics.class);
        ReservationRecovery recovery = new ReservationRecovery(orders, placement,
                new ReservationRecoveryPolicy(50, Duration.ofSeconds(30)), metrics);

        recovery.recoverPending();

        verify(placement).defer(failing);
        verify(placement).resolve(succeeding);
        verify(metrics).retry();
        verify(metrics).resolved();
    }
}
