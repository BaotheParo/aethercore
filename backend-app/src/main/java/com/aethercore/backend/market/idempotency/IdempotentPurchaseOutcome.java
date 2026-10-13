package com.aethercore.backend.market.idempotency;

import com.aethercore.backend.market.PurchaseResult;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable business outcome of an idempotent market purchase attempt.
 */
public record IdempotentPurchaseOutcome(
        PurchaseResult result,
        UUID purchaseId,
        boolean replayed
) {
    public IdempotentPurchaseOutcome {
        Objects.requireNonNull(result, "result must not be null");
        if (result == PurchaseResult.SUCCESS && purchaseId == null) {
            throw new IllegalArgumentException("purchaseId must not be null for SUCCESS");
        }
        if (result == PurchaseResult.NOT_AVAILABLE && purchaseId != null) {
            throw new IllegalArgumentException("purchaseId must be null for NOT_AVAILABLE");
        }
    }

    /**
     * Compares core business outcomes (result and purchaseId) ignoring metadata flags like replayed.
     */
    public boolean matchesBusinessOutcome(IdempotentPurchaseOutcome other) {
        if (other == null) {
            return false;
        }
        return this.result == other.result && Objects.equals(this.purchaseId, other.purchaseId);
    }
}