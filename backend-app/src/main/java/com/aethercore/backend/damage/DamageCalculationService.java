package com.aethercore.backend.damage;

import com.aethercore.core.m1e3.CharacterStats;
import com.aethercore.core.m1e4.DamageStrategy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Spring-managed DamageCalculationService demonstrating Constructor Injection with @Qualifier.
 */
@Service
public class DamageCalculationService {

    private final DamageStrategy damageStrategy;

    // Explicit Constructor Injection with @Qualifier to resolve multiple beans of type DamageStrategy
    public DamageCalculationService(@Qualifier("physicalDamageStrategy") DamageStrategy damageStrategy) {
        this.damageStrategy = Objects.requireNonNull(damageStrategy, "DamageStrategy must not be null");
    }

    public long calculateDamage(CharacterStats stats) {
        Objects.requireNonNull(stats, "CharacterStats must not be null");
        return damageStrategy.calculateDamage(stats);
    }

    public DamageStrategy getDamageStrategy() {
        return damageStrategy;
    }
}
