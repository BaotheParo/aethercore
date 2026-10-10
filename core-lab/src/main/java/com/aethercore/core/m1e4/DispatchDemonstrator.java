package com.aethercore.core.m1e4;

/**
 * DispatchDemonstrator (M1-E4 Dispatch Lab Fixture):
 * Demonstrates the fundamental difference between:
 * 1. Static Overload Resolution (decided at compile-time based on static reference type).
 * 2. Dynamic Method Overriding (decided at runtime based on actual runtime instance type).
 */
public class DispatchDemonstrator {

    public String describe(DamageStrategy strategy) {
        return "BASE_STRATEGY_OVERLOAD";
    }

    public String describe(PhysicalDamageStrategy strategy) {
        return "PHYSICAL_STRATEGY_OVERLOAD";
    }

    public String describe(MagicalDamageStrategy strategy) {
        return "MAGICAL_STRATEGY_OVERLOAD";
    }
}
