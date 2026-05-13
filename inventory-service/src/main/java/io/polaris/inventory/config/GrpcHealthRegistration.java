package io.polaris.inventory.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import io.grpc.BindableService;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.HealthStatusManager;

import net.devh.boot.grpc.server.event.GrpcServerStartedEvent;

@Configuration
public class GrpcHealthRegistration {
    private final HealthStatusManager healthStatusManager;
    private final List<BindableService> services;

    public GrpcHealthRegistration(HealthStatusManager healthStatusManager, List<BindableService> services) {
        this.healthStatusManager = healthStatusManager;
        this.services = services;
    }

    @EventListener(GrpcServerStartedEvent.class)
    void registerServicesAsServing(GrpcServerStartedEvent event) {
        for (BindableService service : services) {
            String serviceName = service.bindService().getServiceDescriptor().getName();
            healthStatusManager.setStatus(serviceName, ServingStatus.SERVING);
        }
    }
}
