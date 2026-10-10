package com.aethercore.core.m1e3;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

/**
 * M1-E3 Test Suite 1:
 * Laboratory experiments on Primitives, Wrapper Objects, Autoboxing/Unboxing,
 * Integer Cache contracts, and BigDecimal precision vs double representation.
 */
public class WrapperAndPrecisionExperimentsTest {

    @Nested
    @DisplayName("Thí nghiệm B1: Primitive, Wrapper & Autoboxing/Unboxing")
    class PrimitiveWrapperTests {

        @Test
        @DisplayName("Primitive == compares value directly")
        void primitiveValueComparison() {
            int a = 100;
            int b = 100;
            assertTrue(a == b, "Primitive == must compare numerical values directly");
        }

        @Test
        @DisplayName("Wrapper == compares object reference identity, whereas equals() compares value")
        void wrapperIdentityVsEquals() {
            Integer w1 = Integer.valueOf(1000);
            Integer w2 = Integer.valueOf(1000);

            // Outside cache: w1 and w2 are distinct object instances on Heap
            assertFalse(w1 == w2, "Distinct wrapper instances outside cache must not be == ");
            assertTrue(w1.equals(w2), "Wrapper equals() must compare underlying wrapped primitive value");
        }

        @Test
        @DisplayName("Comparing Wrapper with Primitive unboxes Wrapper to primitive")
        void mixedComparisonTriggersUnboxing() {
            Integer wrapperVal = Integer.valueOf(500);
            int primitiveVal = 500;

            // In mixed == comparison, Java unboxes wrapperVal to int (wrapperVal.intValue() == primitiveVal)
            assertTrue(wrapperVal == primitiveVal, "Comparing wrapper with primitive triggers unboxing and compares values");
        }

        @Test
        @DisplayName("Unboxing a null Wrapper throws NullPointerException")
        void unboxingNullThrowsNPE() {
            Integer nullWrapper = null;

            // Implicit unboxing on null reference calls nullWrapper.intValue(), resulting in NPE
            assertThrows(NullPointerException.class, () -> {
                int val = nullWrapper; // Triggers unboxing
            }, "Unboxing null wrapper must throw NullPointerException");
        }
    }

    @Nested
    @DisplayName("Thí nghiệm B2: Integer Cache Contract & Observation")
    class IntegerCacheTests {

        @Test
        @DisplayName("Values within [-128, 127] are guaranteed cached by JLS §5.1.7")
        void guaranteedCacheRange() {
            Integer low1 = Integer.valueOf(-128);
            Integer low2 = Integer.valueOf(-128);
            assertTrue(low1 == low2, "-128 must be cached and return identical object reference");

            Integer high1 = Integer.valueOf(127);
            Integer high2 = Integer.valueOf(127);
            assertTrue(high1 == high2, "127 must be cached and return identical object reference");

            Integer zero1 = 0; // Autoboxing uses Integer.valueOf()
            Integer zero2 = 0;
            assertTrue(zero1 == zero2, "0 autoboxing must use Integer cache");
        }

        @Test
        @DisplayName("Values outside [-128, 127] are not required to be cached (Observation on standard JVM)")
        void outsideCacheObservation() {
            Integer out1 = Integer.valueOf(128);
            Integer out2 = Integer.valueOf(128);

            // Contract check: equals is ALWAYS true
            assertTrue(out1.equals(out2), "equals() is guaranteed true for equal values");

            // Observation on standard OpenJDK without custom -XX:AutoBoxCacheMax:
            // out1 and out2 are separate instances
            assertFalse(out1 == out2, "Observation: 128 is not cached by default and yields separate references");
        }
    }

    @Nested
    @DisplayName("Thí nghiệm B3: Double binary floating-point vs BigDecimal creation")
    class BigDecimalCreationTests {

        @Test
        @DisplayName("Double arithmetic suffers from binary floating-point representation inaccuracy")
        void doubleInaccuracy() {
            double d = 0.1 + 0.2;
            // In IEEE 754 floating point, 0.1 + 0.2 = 0.30000000000000004
            assertNotEquals(0.3, d, "0.1 + 0.2 in double does not equal 0.3 exactly");
        }

        @Test
        @DisplayName("new BigDecimal(double) captures binary approximation error, whereas String constructor is exact")
        void bigDecimalConstructionComparison() {
            // new BigDecimal(double) passes the already-inexact IEEE 754 double representation
            BigDecimal fromDouble = new BigDecimal(0.1);
            assertThat(fromDouble.toString()).startsWith("0.1000000000000000055511151231257827021181583404541015625");

            // new BigDecimal(String) parses the exact decimal characters
            BigDecimal fromString = new BigDecimal("0.1");
            assertEquals("0.1", fromString.toString());

            // BigDecimal.valueOf(double) uses Double.toString(double) internally, producing "0.1"
            BigDecimal fromValueOf = BigDecimal.valueOf(0.1);
            assertEquals("0.1", fromValueOf.toString());
            assertEquals(fromString, fromValueOf);
        }
    }

    @Nested
    @DisplayName("Thí nghiệm B4: Scale, Equality, Rounding & OrderPriceCalculator")
    class ScaleAndCalculatorTests {

        @Test
        @DisplayName("BigDecimal.equals() compares value AND scale, while compareTo() compares numerical value only")
        void equalsVsCompareToScale() {
            BigDecimal a = new BigDecimal("2.0");  // scale = 1
            BigDecimal b = new BigDecimal("2.00"); // scale = 2

            assertFalse(a.equals(b), "BigDecimal.equals() must return false because scale 1 != scale 2");
            assertEquals(0, a.compareTo(b), "BigDecimal.compareTo() must return 0 because numerical values are equal");
        }

        @Test
        @DisplayName("Inexact division without RoundingMode throws ArithmeticException")
        void inexactDivisionThrowsArithmeticException() {
            BigDecimal one = BigDecimal.ONE;
            BigDecimal three = new BigDecimal("3");

            assertThrows(ArithmeticException.class, () -> {
                one.divide(three); // 1/3 has infinite recurring decimals
            }, "Dividing 1 by 3 without specifying scale/RoundingMode must throw ArithmeticException");

            // With explicit scale and RoundingMode
            BigDecimal result = one.divide(three, 4, RoundingMode.HALF_UP);
            assertEquals(new BigDecimal("0.3333"), result);
        }

        @Test
        @DisplayName("OrderPriceCalculator executes exact total calculation according to lab contract")
        void calculatorValidCases() {
            BigDecimal total = OrderPriceCalculator.calculateTotalPrice("19.99", 3);
            assertEquals(new BigDecimal("59.97"), total);
            assertEquals(2, total.scale());

            BigDecimal totalWhole = OrderPriceCalculator.calculateTotalPrice("10", 5);
            assertEquals(new BigDecimal("50.00"), totalWhole);
            assertEquals(2, totalWhole.scale());
        }

        @Test
        @DisplayName("OrderPriceCalculator rejects invalid inputs and scale policy violations")
        void calculatorRejections() {
            // Negative price
            assertThatThrownBy(() -> OrderPriceCalculator.calculateTotalPrice("-5.00", 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unit price must be non-negative");

            // Non-positive quantity
            assertThatThrownBy(() -> OrderPriceCalculator.calculateTotalPrice("10.00", 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Quantity must be strictly positive");

            // Scale violation (> 2 decimals without rounding)
            assertThatThrownBy(() -> OrderPriceCalculator.calculateTotalPrice("10.999", 2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unit price exceeds maximum allowed scale of 2");

            // Malformed number string
            assertThatThrownBy(() -> OrderPriceCalculator.calculateTotalPrice("abc", 2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid decimal price format");
        }
    }
}
