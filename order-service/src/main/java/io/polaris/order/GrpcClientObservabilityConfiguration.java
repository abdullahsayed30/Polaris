package io.polaris.order;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import io.grpc.ClientInterceptor;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcClientInterceptor;
import io.micrometer.observation.ObservationRegistry;

import io.polaris.order.adapter.out.grpc.GrpcClientLoggingInterceptor;

import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor;

@Configuration
public class GrpcClientObservabilityConfiguration {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @GrpcGlobalClientInterceptor
    ClientInterceptor grpcClientLoggingInterceptor() {
        return new GrpcClientLoggingInterceptor();
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    @GrpcGlobalClientInterceptor
    ClientInterceptor grpcClientObservationInterceptor(ObservationRegistry observationRegistry) {
        return new ObservationGrpcClientInterceptor(observationRegistry);
    }
}
