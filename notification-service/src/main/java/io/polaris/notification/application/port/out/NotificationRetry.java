package io.polaris.notification.application.port.out;

public interface NotificationRetry {
    void execute(Runnable handler);
}
