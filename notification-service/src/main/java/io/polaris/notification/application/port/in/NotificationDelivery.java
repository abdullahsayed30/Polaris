package io.polaris.notification.application.port.in;

public record NotificationDelivery(
        String topic,
        int partition,
        long offset,
        String key,
        String payload,
        String eventType) {
}
