package com.aethercore.backend.market.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA entity mapping market_purchase.
 */
@Entity
@Table(name = "market_purchase")
public class JpaMarketPurchase {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "sku", length = 64, nullable = false)
    private String sku;

    @Column(name = "quantity", nullable = false)
    private long quantity;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected JpaMarketPurchase() {
    }

    public JpaMarketPurchase(UUID id, String sku, long quantity) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.sku = Objects.requireNonNull(sku, "sku must not be null");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        this.quantity = quantity;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public long getQuantity() {
        return quantity;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}