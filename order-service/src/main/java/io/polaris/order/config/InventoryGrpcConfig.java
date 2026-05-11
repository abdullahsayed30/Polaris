package io.polaris.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import io.polaris.inventory.grpc.InventoryServiceGrpc;

@Configuration
public class InventoryGrpcConfig {
    @Bean(destroyMethod = "shutdown")
    ManagedChannel inventoryManagedChannel(InventoryGrpcProperties properties) {
        return ManagedChannelBuilder.forAddress(properties.host(), properties.port())
                .usePlaintext()
                .build();
    }

    @Bean
    InventoryServiceGrpc.InventoryServiceBlockingStub inventoryServiceBlockingStub(ManagedChannel inventoryManagedChannel) {
        return InventoryServiceGrpc.newBlockingStub(inventoryManagedChannel);
    }
}
