package io.polaris.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.polaris.inventory.grpc.InventoryServiceGrpc;

import net.devh.boot.grpc.client.channelfactory.GrpcChannelFactory;

@Configuration
public class InventoryGrpcConfig {
    @Bean
    InventoryServiceGrpc.InventoryServiceBlockingStub inventoryServiceBlockingStub(GrpcChannelFactory channelFactory) {
        return InventoryServiceGrpc.newBlockingStub(channelFactory.createChannel("inventory-service"));
    }
}
