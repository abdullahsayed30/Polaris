package io.polaris.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "polaris.inventory.grpc")
public record InventoryGrpcProperties(String host, int port, Duration deadline) {
    public InventoryGrpcProperties {
        if (deadline == null) {
            deadline = Duration.ofSeconds(2);
        }
    }
}
