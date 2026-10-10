package com.aethercore.core.m1e4;

import com.aethercore.core.m1e3.CharacterStats;
import java.util.Objects;

/**
 * Physical Damage Strategy:
 * Formula: 2 * strength + floor(agility / 2)
 * Employs 64-bit long intermediate arithmetic to prevent integer overflow.
 */
public class PhysicalDamageStrategy implements DamageStrategy {

    @Override
    public long calculateDamage(CharacterStats stats) {
        Objects.requireNonNull(stats, "CharacterStats must not be null");
        long strPart = 2L * stats.strength();
        long agiPart = stats.agility() / 2L;
        return strPart + agiPart;
    }
}
