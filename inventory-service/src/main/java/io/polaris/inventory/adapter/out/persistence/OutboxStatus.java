package io.polaris.inventory.adapter.out.persistence;

public enum OutboxStatus {
    PENDING, RETRY, PUBLISHED, FAILED
}
