package io.polaris.inventory.config;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

import io.polaris.inventory.api.InventoryGrpcController;

@Component
public class InventoryGrpcServer implements SmartLifecycle {
    private final Server server;
    private volatile boolean running;

    public InventoryGrpcServer(InventoryGrpcProperties properties, InventoryGrpcController controller) {
        this.server = NettyServerBuilder.forPort(properties.port())
                .addService(controller)
                .build();
    }

    @Override
    public void start() {
        try {
            server.start();
            running = true;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to start Inventory gRPC server", ex);
        }
    }

    @Override
    public void stop() {
        server.shutdown();
        try {
            if (!server.awaitTermination(10, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            server.shutdownNow();
        } finally {
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public int port() {
        return server.getPort();
    }
}
