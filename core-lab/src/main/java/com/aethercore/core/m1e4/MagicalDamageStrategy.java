package com.aethercore.core.m1e4;

import com.aethercore.core.m1e3.CharacterStats;
import java.util.Objects;

/**
 * Magical Damage Strategy:
 * Formula: 3 * intelligence
 * Employs 64-bit long arithmetic to prevent integer overflow.
 */
public class MagicalDamageStrategy implements DamageStrategy {

    @Override
    public long calculateDamage(CharacterStats stats) {
        Objects.requireNonNull(stats, "CharacterStats must not be null");
        return 3L * stats.intelligence();
    }
}
