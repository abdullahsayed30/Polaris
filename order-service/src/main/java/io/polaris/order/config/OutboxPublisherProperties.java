package io.polaris.order.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("polaris.messaging.outbox")
public record OutboxPublisherProperties(
        @DefaultValue("50") int batchSize,
        @DefaultValue("10") int maxAttempts,
        @DefaultValue("1s") Duration initialBackoff,
        @DefaultValue("1m") Duration maxBackoff,
        @DefaultValue("10s") Duration sendTimeout) {
}
