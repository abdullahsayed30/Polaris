package io.polaris.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "polaris.notifications.retry")
public record NotificationRetryProperties(
        int maxAttempts,
        Duration initialInterval,
        double multiplier
) {
    public NotificationRetryProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (initialInterval == null || initialInterval.isNegative() || initialInterval.isZero()) {
            throw new IllegalArgumentException("initialInterval must be positive");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be at least 1.0");
        }
    }
}
