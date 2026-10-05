package io.polaris.order.application.port.out;

public interface RecoveryMetrics {
    void resolved();
    void retry();
}
