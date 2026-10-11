package com.aethercore.backend.market;

import java.util.Objects;

/**
 * M2-E1B: Immutable snapshot of market stock state at a specific point in time.
 * Used for optimistic concurrency control checks and verification.
 *
 * @param sku Item stock keeping unit (non-null, non-blank)
 * @param available Available quantity (non-negative)
 * @param version Stock version number (non-negative)
 */
public record StockSnapshot(String sku, long available, long version) {

    public StockSnapshot {
        Objects.requireNonNull(sku, "sku must not be null");
        if (sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (available < 0) {
            throw new IllegalArgumentException("available must not be negative: " + available);
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative: " + version);
        }
    }
}