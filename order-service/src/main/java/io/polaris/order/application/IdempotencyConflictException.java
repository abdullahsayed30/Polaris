package io.polaris.order.application;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency key was already used with a different order request: " + idempotencyKey);
    }
}
