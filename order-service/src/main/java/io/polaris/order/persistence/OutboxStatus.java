package io.polaris.order.persistence;

public enum OutboxStatus {
    PENDING, RETRY, PUBLISHED, FAILED
}
