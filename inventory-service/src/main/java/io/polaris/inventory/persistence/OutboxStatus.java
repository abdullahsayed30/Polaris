package io.polaris.inventory.persistence;

public enum OutboxStatus {
    PENDING, RETRY, PUBLISHED, FAILED
}
