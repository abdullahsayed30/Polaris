package io.polaris.order.application.domain.service;

import java.time.Duration;

/** Plain application policy, populated by composition-root configuration. */
public record ReservationRecoveryPolicy(int batchSize, Duration retryDelay) {
    public ReservationRecoveryPolicy {
        if (batchSize < 1 || retryDelay == null || retryDelay.isNegative() || retryDelay.isZero()) {
            throw new IllegalArgumentException("Reservation recovery requires a positive batch size and retry delay");
        }
    }
}
