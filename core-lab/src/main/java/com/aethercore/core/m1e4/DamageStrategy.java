package com.aethercore.core.m1e4;

import com.aethercore.core.m1e3.CharacterStats;

/**
 * Functional Interface for Damage Calculation Strategy:
 * Encapsulates damage calculation algorithms independently of client contexts.
 */
@FunctionalInterface
public interface DamageStrategy {

    /**
     * Calculates total damage output based on character attributes.
     *
     * @param stats Non-null CharacterStats record
     * @return 64-bit long integer damage to prevent arithmetic overflow in the defined input domain
     */
    long calculateDamage(CharacterStats stats);
}
