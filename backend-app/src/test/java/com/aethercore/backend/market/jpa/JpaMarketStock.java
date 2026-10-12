package com.aethercore.backend.market.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.Objects;

/**
 * JPA entity mapping market_stock with @Version.
 * Note: version is managed strictly by Hibernate and never updated manually in Java code.
 */
@Entity
@Table(name = "market_stock")
public class JpaMarketStock {

    @Id
    @Column(name = "sku", length = 64, nullable = false)
    private String sku;

    @Column(name = "available", nullable = false)
    private long available;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected JpaMarketStock() {
    }

    public JpaMarketStock(String sku, long available) {
        this.sku = Objects.requireNonNull(sku, "sku must not be null");
        if (available < 0) {
            throw new IllegalArgumentException("available must not be negative");
        }
        this.available = available;
    }

    public String getSku() {
        return sku;
    }

    public long getAvailable() {
        return available;
    }

    public long getVersion() {
        return version;
    }

    public void decrementStock(long quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive: " + quantity);
        }
        if (this.available < quantity) {
            throw new IllegalStateException("Insufficient stock: available " + this.available + ", requested " + quantity);
        }
        this.available -= quantity;
        // Do NOT modify version field; Hibernate automatically increments it during dirty checking / flush.
    }
}