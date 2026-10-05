package io.polaris.order.adapter.out.observability;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

import io.polaris.order.application.port.out.RecoveryMetrics;

@Component
public class MicrometerRecoveryMetrics implements RecoveryMetrics {
    private final MeterRegistry registry;
    public MicrometerRecoveryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }
    public void resolved() {
        registry.counter("polaris.reservation.recovery", "outcome", "resolved").increment();
    }
    public void retry() {
        registry.counter("polaris.reservation.recovery", "outcome", "retry").increment();
    }
}
