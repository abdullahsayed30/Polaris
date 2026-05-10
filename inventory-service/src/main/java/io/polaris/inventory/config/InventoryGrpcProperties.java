package io.polaris.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "polaris.inventory.grpc")
public record InventoryGrpcProperties(int port) {
}
