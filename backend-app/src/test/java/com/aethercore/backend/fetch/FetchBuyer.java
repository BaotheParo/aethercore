package com.aethercore.backend.fetch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * Test entity representing a buyer in fetch and collection pagination experiments (M2-E4A & M2-E4B).
 */
@Entity
@Table(name = "fetch_buyer")
public class FetchBuyer {

    @Id
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @OneToMany(mappedBy = "buyer", fetch = FetchType.LAZY)
    private List<FetchOrder> orders = new ArrayList<>();

    public FetchBuyer() {
    }

    public FetchBuyer(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<FetchOrder> getOrders() {
        return orders;
    }
}
