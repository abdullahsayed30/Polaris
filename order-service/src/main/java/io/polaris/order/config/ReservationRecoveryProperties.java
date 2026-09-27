package io.polaris.order.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("polaris.reservation.recovery")
public record ReservationRecoveryProperties(
        @DefaultValue("50") int batchSize,
        @DefaultValue("30s") Duration retryDelay) {
    public ReservationRecoveryProperties {
        if (batchSize < 1 || retryDelay == null || retryDelay.isNegative() || retryDelay.isZero()) {
            throw new IllegalArgumentException("Reservation recovery requires a positive batch size and retry delay");
        }
    }
}
