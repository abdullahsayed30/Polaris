package io.polaris.inventory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import io.grpc.ServerInterceptor;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor;
import io.micrometer.observation.ObservationRegistry;

import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;

@Configuration
public class GrpcServerObservabilityConfiguration {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @GrpcGlobalServerInterceptor
    ServerInterceptor grpcServerLoggingInterceptor() {
        return new GrpcServerLoggingInterceptor();
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    @GrpcGlobalServerInterceptor
    ServerInterceptor grpcServerObservationInterceptor(ObservationRegistry observationRegistry) {
        return new ObservationGrpcServerInterceptor(observationRegistry);
    }
}
