package com.aethercore.backend.market.http;

/**
 * Exception thrown when a completed purchase request cannot be found for caller and idempotency key.
 */
public class PurchaseRequestNotFoundException extends RuntimeException {
    public PurchaseRequestNotFoundException(String message) {
        super(message);
    }
}
