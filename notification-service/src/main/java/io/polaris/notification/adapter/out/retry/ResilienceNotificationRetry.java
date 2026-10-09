package io.polaris.notification.adapter.out.retry;

import org.springframework.stereotype.Component;

import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.MeterRegistry;

import io.polaris.notification.adapter.out.observability.DurableTelemetry;
import io.polaris.notification.application.port.out.NotificationRetry;

@Component
public class ResilienceNotificationRetry implements NotificationRetry {
    private final Retry retry;
    private final DurableTelemetry telemetry;
    public ResilienceNotificationRetry(Retry retry, DurableTelemetry telemetry, MeterRegistry metrics) {
        this.telemetry = telemetry;
        metrics.counter("polaris.notification.retries");
        retry.getEventPublisher().onRetry(event -> metrics.counter("polaris.notification.retries").increment());
        this.retry = retry;
    }
    public void execute(Runnable handler) {
        Retry.decorateRunnable(retry, () -> {
            try (var session = telemetry.attempt("notification.handler.attempt", null)) {
                try {
                    handler.run();
                    session.outcome("simulated");
                } catch (RuntimeException ex) {
                    session.error(ex);
                    throw ex;
                }
            }
        }).run();
    }
}
