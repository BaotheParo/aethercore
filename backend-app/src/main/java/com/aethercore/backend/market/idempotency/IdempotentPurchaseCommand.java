package com.aethercore.backend.market.idempotency;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable command representing an idempotent market purchase request.
 */
public record IdempotentPurchaseCommand(
        UUID callerId,
        UUID idempotencyKey,
        String sku,
        int quantity
) {
    public static final String OPERATION = "PURCHASE_V1";
    public static final int MAX_SKU_LENGTH = 64;

    public IdempotentPurchaseCommand {
        Objects.requireNonNull(callerId, "callerId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be null or blank");
        }
        if (sku.length() > MAX_SKU_LENGTH) {
            throw new IllegalArgumentException("sku length must not exceed " + MAX_SKU_LENGTH);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be strictly positive (> 0)");
        }
    }
}