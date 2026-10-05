package io.polaris.notification.adapter.out.retry;

import org.springframework.stereotype.Component;

import io.github.resilience4j.retry.Retry;

import io.polaris.notification.application.port.out.NotificationRetry;

@Component
public class ResilienceNotificationRetry implements NotificationRetry {
    private final Retry retry;
    public ResilienceNotificationRetry(Retry retry) {
        this.retry = retry;
    }
    public void execute(Runnable handler) {
        Retry.decorateRunnable(retry, handler).run();
    }
}
