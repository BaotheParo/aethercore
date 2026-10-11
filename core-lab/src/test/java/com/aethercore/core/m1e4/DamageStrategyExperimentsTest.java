package com.aethercore.core.m1e4;

import com.aethercore.core.m1e3.CharacterStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

/**
 * M1-E4 Core Lab Test Suite:
 * Strategy pattern computations (Prompt 05/06 formulas), boundary checks, overflow prevention,
 * lambda delegation, and Compile-time Overload Resolution vs Runtime Dynamic Dispatch.
 */
public class DamageStrategyExperimentsTest {

    @Nested
    @DisplayName("Thí nghiệm 1: Strategy Pattern Calculations & Boundaries")
    class StrategyCalculationTests {

        @Test
        @DisplayName("Asymmetric stats (10, 12, 4) calculates Physical=24L and Magical=28L")
        void asymmetricCalculation() {
            DamageStrategy physical = new PhysicalDamageStrategy();
            DamageStrategy magical = new MagicalDamageStrategy();

            // Stats (str=10, int=12, agi=4)
            CharacterStats stats = new CharacterStats(10, 12, 4);

            // Physical: 2 * 10 + 4 = 24L
            assertEquals(24L, physical.calculateDamage(stats));

            // Magical: 2 * 12 + 4 = 28L
            assertEquals(28L, magical.calculateDamage(stats));
        }

        @Test
        @DisplayName("Zero stats (0, 0, 0) yields 0L for both strategies")
        void zeroStatsCalculation() {
            CharacterStats zeroStats = new CharacterStats(0, 0, 0);
            assertEquals(0L, new PhysicalDamageStrategy().calculateDamage(zeroStats));
            assertEquals(0L, new MagicalDamageStrategy().calculateDamage(zeroStats));
        }

        @Test
        @DisplayName("Extreme stats (Integer.MAX_VALUE) calculates 6442450941L without 32-bit overflow")
        void largeStatsOverflowProtection() {
            DamageStrategy physical = new PhysicalDamageStrategy();
            DamageStrategy magical = new MagicalDamageStrategy();

            // Integer.MAX_VALUE = 2,147,483,647
            CharacterStats extremeStats = new CharacterStats(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

            // Expected literal: 2 * 2147483647 + 2147483647 = 6,442,450,941L
            long expected = 6442450941L;
            assertEquals(expected, physical.calculateDamage(extremeStats));
            assertEquals(expected, magical.calculateDamage(extremeStats));
            assertTrue(expected > (long) Integer.MAX_VALUE, "Calculation must exceed 32-bit integer range safely");
        }

        @Test
        @DisplayName("Damage strategies enforce Null Safety on input stats")
        void nullSafetyEnforcement() {
            DamageStrategy physical = new PhysicalDamageStrategy();
            DamageStrategy magical = new MagicalDamageStrategy();

            assertThrows(NullPointerException.class, () -> physical.calculateDamage(null));
            assertThrows(NullPointerException.class, () -> magical.calculateDamage(null));
        }
    }

    @Nested
    @DisplayName("Thí nghiệm 2: DamageCalculator Constructor Injection & Lambda Delegation")
    class DamageCalculatorTests {

        @Test
        @DisplayName("DamageCalculator delegates computation to injected strategy")
        void calculatorExecution() {
            CharacterStats stats = new CharacterStats(10, 12, 4);

            DamageCalculator physicalCalc = new DamageCalculator(new PhysicalDamageStrategy());
            assertEquals(24L, physicalCalc.compute(stats));

            DamageCalculator magicalCalc = new DamageCalculator(new MagicalDamageStrategy());
            assertEquals(28L, magicalCalc.compute(stats));
        }

        @Test
        @DisplayName("FunctionalInterface allows lambda stub injection to verify delegation")
        void lambdaStubDelegation() {
            CharacterStats stats = new CharacterStats(10, 10, 10);

            // Injected lambda returning constant 999L
            DamageStrategy constantStub = s -> 999L;
            DamageCalculator calc = new DamageCalculator(constantStub);

            assertEquals(999L, calc.compute(stats));
            assertSame(constantStub, calc.getStrategy());
        }

        @Test
        @DisplayName("DamageCalculator validates constructor dependencies and input stats")
        void constructorValidation() {
            assertThatThrownBy(() -> new DamageCalculator(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("DamageStrategy must not be null");

            DamageCalculator calc = new DamageCalculator(new PhysicalDamageStrategy());
            assertThatThrownBy(() -> calc.compute(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("CharacterStats must not be null");
        }
    }

    @Nested
    @DisplayName("Thí nghiệm 3: Static Overload Resolution vs Runtime Dynamic Override Dispatch")
    class MethodDispatchTests {

        @Test
        @DisplayName("Method Overload is resolved at COMPILE-TIME, while Override is dispatched at RUNTIME")
        void dispatchResolutionComparison() {
            DispatchDemonstrator demonstrator = new DispatchDemonstrator();
            CharacterStats stats = new CharacterStats(10, 12, 4);

            // Variable declared with static type DamageStrategy, holding runtime instance PhysicalDamageStrategy
            DamageStrategy polymorphicRef = new PhysicalDamageStrategy();

            // 1. Static Overload Resolution (JLS §15.12.2):
            // Compiler looks at the STATIC TYPE of polymorphicRef (which is DamageStrategy) at compile time.
            // Therefore, it binds to describe(DamageStrategy), NOT describe(PhysicalDamageStrategy)!
            String overloadResult = demonstrator.describe(polymorphicRef);
            assertEquals("BASE_STRATEGY_OVERLOAD", overloadResult,
                    "Overload resolution is static (compile-time) based on reference type");

            // Direct specific reference binds to specific overload at compile time
            PhysicalDamageStrategy specificRef = new PhysicalDamageStrategy();
            assertEquals("PHYSICAL_STRATEGY_OVERLOAD", demonstrator.describe(specificRef));

            // 2. Dynamic Method Overriding (JLS §15.12.4):
            // Runtime looks at the ACTUAL OBJECT INSTANCE on Heap (PhysicalDamageStrategy).
            // It executes PhysicalDamageStrategy.calculateDamage (2*10 + 4 = 24L), NOT an abstract/base method!
            long overrideResult = polymorphicRef.calculateDamage(stats);
            assertEquals(24L, overrideResult,
                    "Override resolution is dynamic (runtime) based on actual instance type");
        }
    }
}
