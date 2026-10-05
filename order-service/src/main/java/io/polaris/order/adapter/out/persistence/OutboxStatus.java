package io.polaris.order.adapter.out.persistence;

public enum OutboxStatus {
    PENDING, RETRY, PUBLISHED, FAILED
}
