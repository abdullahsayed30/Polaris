package io.polaris.order.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "polaris.inventory.grpc")
public record InventoryGrpcProperties(String host, int port, Duration deadline) {
    public InventoryGrpcProperties {
        if (deadline == null) {
            deadline = Duration.ofSeconds(2);
        }
    }
}
