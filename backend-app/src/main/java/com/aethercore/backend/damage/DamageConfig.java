package com.aethercore.backend.damage;

import com.aethercore.core.m1e4.DamageStrategy;
import com.aethercore.core.m1e4.MagicalDamageStrategy;
import com.aethercore.core.m1e4.PhysicalDamageStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Configuration defining DamageStrategy beans for DI wiring demonstration.
 */
@Configuration
public class DamageConfig {

    @Bean("physicalDamageStrategy")
    public DamageStrategy physicalDamageStrategy() {
        return new PhysicalDamageStrategy();
    }

    @Bean("magicalDamageStrategy")
    public DamageStrategy magicalDamageStrategy() {
        return new MagicalDamageStrategy();
    }
}
