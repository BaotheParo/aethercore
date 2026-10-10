package com.aethercore.core.m1e4;

import com.aethercore.core.m1e3.CharacterStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

/**
 * M1-E4 Core Lab Test Suite:
 * Strategy pattern computations, boundary checks, overflow prevention,
 * and Compile-time Overload Resolution vs Runtime Dynamic Dispatch.
 */
public class DamageStrategyExperimentsTest {

    @Nested
    @DisplayName("Thí nghiệm 1: Strategy Pattern Calculations & Boundaries")
    class StrategyCalculationTests {

        @Test
        @DisplayName("PhysicalDamageStrategy calculates 2*strength + floor(agility/2)")
        void physicalCalculation() {
            DamageStrategy physical = new PhysicalDamageStrategy();

            // stats(str=18, int=10, agi=15) -> 2*18 + floor(15/2) = 36 + 7 = 43
            CharacterStats stats = new CharacterStats(18, 10, 15);
            assertEquals(43L, physical.calculateDamage(stats));

            // stats(str=0, int=0, agi=0) -> 0
            assertEquals(0L, physical.calculateDamage(new CharacterStats(0, 0, 0)));
        }

        @Test
        @DisplayName("MagicalDamageStrategy calculates 3*intelligence")
        void magicalCalculation() {
            DamageStrategy magical = new MagicalDamageStrategy();

            // stats(str=10, int=16, agi=12) -> 3*16 = 48
            CharacterStats stats = new CharacterStats(10, 16, 12);
            assertEquals(48L, magical.calculateDamage(stats));
        }

        @Test
        @DisplayName("Damage calculation handles large stats without 32-bit integer overflow")
        void largeStatsOverflowProtection() {
            DamageStrategy physical = new PhysicalDamageStrategy();
            DamageStrategy magical = new MagicalDamageStrategy();

            // Integer.MAX_VALUE = 2,147,483,647
            CharacterStats extremeStats = new CharacterStats(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

            // Physical: 2 * 2147483647 + (2147483647 / 2) = 4,294,967,294 + 1,073,741,823 = 5,368,709,117L
            long expectedPhysical = 2L * Integer.MAX_VALUE + (Integer.MAX_VALUE / 2L);
            long actualPhysical = physical.calculateDamage(extremeStats);
            assertEquals(expectedPhysical, actualPhysical);
            assertTrue(actualPhysical > (long) Integer.MAX_VALUE, "Result must exceed 32-bit integer capacity cleanly");

            // Magical: 3 * 2147483647 = 6,442,450,941L
            long expectedMagical = 3L * (long) Integer.MAX_VALUE;
            assertEquals(expectedMagical, magical.calculateDamage(extremeStats));
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
    @DisplayName("Thí nghiệm 2: DamageCalculator Constructor Injection (Pure Java DI)")
    class DamageCalculatorTests {

        @Test
        @DisplayName("DamageCalculator executes chosen strategy through polymorphism")
        void calculatorExecution() {
            CharacterStats stats = new CharacterStats(20, 15, 10);

            DamageCalculator physicalCalc = new DamageCalculator(new PhysicalDamageStrategy());
            // Physical: 2*20 + 10/2 = 45
            assertEquals(45L, physicalCalc.compute(stats));

            DamageCalculator magicalCalc = new DamageCalculator(new MagicalDamageStrategy());
            // Magical: 3*15 = 45
            assertEquals(45L, magicalCalc.compute(stats));
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
            CharacterStats stats = new CharacterStats(10, 10, 10);

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
            // Runtime looks at the ACTUAL OBJECT INSTANCE on Heap (PhysicalDamageStrategy) via vtable.
            // It executes PhysicalDamageStrategy.calculateDamage (2*10 + 10/2 = 25), NOT an abstract/base method!
            long overrideResult = polymorphicRef.calculateDamage(stats);
            assertEquals(25L, overrideResult,
                    "Override resolution is dynamic (runtime) based on actual instance type");
        }
    }
}
