package com.aethercore.backend.fetch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Test entity representing a buyer in fetch experiments (M2-E4A).
 */
@Entity
@Table(name = "fetch_buyer")
public class FetchBuyer {

    @Id
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

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
}
