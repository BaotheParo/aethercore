package com.aethercore.backend.market.idempotency;

/**
 * Domain exception thrown when an idempotency key is reused with a different payload or fingerprint.
 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}