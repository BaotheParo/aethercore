package com.aethercore.backend.fetch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Test entity representing an order in fetch experiments (M2-E4A).
 * Unidirectional ManyToOne association to FetchBuyer with FetchType.LAZY.
 */
@Entity
@Table(name = "fetch_order")
public class FetchOrder {

    @Id
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_id", nullable = false)
    private FetchBuyer buyer;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    public FetchOrder() {
    }

    public FetchOrder(Long id, FetchBuyer buyer, int quantity) {
        this.id = id;
        this.buyer = buyer;
        this.quantity = quantity;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public FetchBuyer getBuyer() {
        return buyer;
    }

    public void setBuyer(FetchBuyer buyer) {
        this.buyer = buyer;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}
