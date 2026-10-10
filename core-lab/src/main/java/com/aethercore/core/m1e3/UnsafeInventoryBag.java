package com.aethercore.core.m1e3;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * UnsafeInventoryBag Record (M1-E3 Experimental Fixture):
 * Directly assigns the incoming List reference without defensive copy.
 * Used in tests to illustrate how external code can mutate record internal state.
 */
public record UnsafeInventoryBag(UUID playerId, List<String> items) {

    public UnsafeInventoryBag {
        Objects.requireNonNull(playerId, "playerId must not be null");
        Objects.requireNonNull(items, "items list must not be null");
        // Intentionally NO defensive copy to show reference leakage
    }
}
