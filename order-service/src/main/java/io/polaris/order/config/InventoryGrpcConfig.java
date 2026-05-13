package io.polaris.order.config;

import org.springframework.context.annotation.Configuration;

import io.polaris.inventory.grpc.InventoryServiceGrpc.InventoryServiceBlockingStub;

import net.devh.boot.grpc.client.inject.GrpcClient;
import net.devh.boot.grpc.client.inject.GrpcClientBean;

@Configuration
@GrpcClientBean(clazz = InventoryServiceBlockingStub.class, beanName = "inventoryGrpc", client = @GrpcClient("inventory-service"))
public class InventoryGrpcConfig {
}
