package com.aethercore.core.m1e3;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * InventoryBag Record (M1-E3 Shallow Immutability Demo):
 * Demonstrates that Records only guarantee SHALLOW immutability by default.
 * To achieve true immutability when holding mutable references (like List),
 * defensive copying and unmodifiable wrapping must be applied in the compact constructor.
 */
public record InventoryBag(UUID playerId, List<String> items) {

    public InventoryBag {
        Objects.requireNonNull(playerId, "playerId must not be null");
        Objects.requireNonNull(items, "items list must not be null");
        // Defensive copy + unmodifiable wrapper to prevent external mutation
        items = List.copyOf(items);
    }
}
