package io.polaris.notification.application;

public record NotificationDelivery(
        String topic,
        int partition,
        long offset,
        String key,
        String payload,
        String eventType) {
}
