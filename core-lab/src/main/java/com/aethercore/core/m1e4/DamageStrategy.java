package com.aethercore.core.m1e4;

import com.aethercore.core.m1e3.CharacterStats;

/**
 * Strategy Interface for Damage Calculation:
 * Encapsulates damage algorithms independently of client contexts.
 */
public interface DamageStrategy {

    /**
     * Calculates total damage output based on character attributes.
     *
     * @param stats Non-null CharacterStats record
     * @return 64-bit long integer damage to prevent arithmetic overflow
     */
    long calculateDamage(CharacterStats stats);
}
