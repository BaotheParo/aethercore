package com.aethercore.core.m1e3;

/**
 * CharacterStats Record (M1-E3 Baseline Record):
 * Demonstrates modern Java Record features:
 * 1. Compact constructor validation.
 * 2. Immutable shallow fields (components are private final).
 * 3. Automatically generated accessor methods, equals(), hashCode(), and toString().
 */
public record CharacterStats(int strength, int intelligence, int agility) {

    public CharacterStats {
        if (strength < 0 || intelligence < 0 || agility < 0) {
            throw new IllegalArgumentException("Stats components must be non-negative");
        }
    }

    /**
     * Helper to create a new record instance with modified strength (wither pattern).
     */
    public CharacterStats withStrength(int newStrength) {
        return new CharacterStats(newStrength, this.intelligence, this.agility);
    }
}
