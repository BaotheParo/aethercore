package com.aethercore.core.m1e4;

import com.aethercore.core.m1e3.CharacterStats;
import java.util.Objects;

/**
 * DamageCalculator (Context in Strategy Pattern):
 * Decouples damage computation from concrete strategy implementations.
 * Pure Java Constructor Injection (zero Spring dependencies in core-lab).
 */
public final class DamageCalculator {

    private final DamageStrategy strategy;

    /**
     * Explicit Constructor Injection ensuring immutability and valid state.
     */
    public DamageCalculator(DamageStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "DamageStrategy must not be null");
    }

    public long compute(CharacterStats stats) {
        Objects.requireNonNull(stats, "CharacterStats must not be null");
        return strategy.calculateDamage(stats);
    }

    public DamageStrategy getStrategy() {
        return strategy;
    }
}
