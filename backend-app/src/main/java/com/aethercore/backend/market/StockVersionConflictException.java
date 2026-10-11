package com.aethercore.backend.market;

import java.util.Objects;

/**
 * M2-E1B: Thrown when an optimistic concurrency control update fails
 * because the stock version has changed since the snapshot was read (stale version conflict).
 */
public class StockVersionConflictException extends RuntimeException {

    private final String sku;
    private final long expectedVersion;

    public StockVersionConflictException(String sku, long expectedVersion) {
        super(String.format("Optimistic lock conflict on stock for sku '%s': expected version %d", sku, expectedVersion));
        this.sku = Objects.requireNonNull(sku, "sku must not be null");
        this.expectedVersion = expectedVersion;
    }

    public StockVersionConflictException(String sku, long expectedVersion, String message) {
        super(message);
        this.sku = Objects.requireNonNull(sku, "sku must not be null");
        this.expectedVersion = expectedVersion;
    }

    public String getSku() {
        return sku;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }
}