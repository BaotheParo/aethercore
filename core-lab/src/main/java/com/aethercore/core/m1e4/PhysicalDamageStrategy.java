package com.aethercore.core.m1e4;

import com.aethercore.core.m1e3.CharacterStats;
import java.util.Objects;

/**
 * Physical Damage Strategy (Prompt 05/06 Contract):
 * Formula: 2 * strength + agility
 * Promoted to 64-bit long arithmetic from the multiplication to prevent 32-bit integer overflow.
 */
public class PhysicalDamageStrategy implements DamageStrategy {

    @Override
    public long calculateDamage(CharacterStats stats) {
        Objects.requireNonNull(stats, "CharacterStats must not be null");
        return 2L * stats.strength() + stats.agility();
    }
}
