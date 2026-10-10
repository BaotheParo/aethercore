package com.aethercore.core.m1e3;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

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
        @DisplayName("Wrapper == compares object reference identity, whereas Objects.equals() compares value")
        void wrapperIdentityVsEquals() {
            Integer w1 = Integer.valueOf(1000);
            Integer w2 = Integer.valueOf(1000);

            // Contract: Objects.equals must always be true for equal values (null-safe)
            assertTrue(Objects.equals(w1, w2), "Wrapper values are equal according to Objects.equals()");

            // Observation on standard OpenJDK without custom -XX:AutoBoxCacheMax:
            // w1 and w2 are distinct instances on Heap
            assertFalse(w1 == w2, "Observation: Distinct wrapper instances outside cache are not == on standard JVM");
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
        @DisplayName("Values outside [-128, 127] satisfy Objects.equals() contract; == is a runtime observation")
        void outsideCacheObservation() {
            Integer out1 = Integer.valueOf(128);
            Integer out2 = Integer.valueOf(128);

            // Contract check: Objects.equals is ALWAYS guaranteed true for equal values
            assertTrue(Objects.equals(out1, out2), "Objects.equals() is guaranteed true for equal values");

            // Observation on standard OpenJDK without custom -XX:AutoBoxCacheMax:
            // out1 and out2 are separate instances. Note: if -XX:AutoBoxCacheMax is >= 128, out1 == out2 could be true.
            boolean sameReference = (out1 == out2);
            assertFalse(sameReference, "Observation: On standard JVM defaults, 128 yields separate object references");
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

        @Test
        @DisplayName("BigDecimal.valueOf cannot restore exact decimal after double arithmetic has already introduced error")
        void valueOfCannotRestoreCalculatedDouble() {
            double calculatedSum = 0.1 + 0.2; // 0.30000000000000004
            BigDecimal bdFromDoubleSum = BigDecimal.valueOf(calculatedSum);
            assertEquals("0.30000000000000004", bdFromDoubleSum.toString());
            assertNotEquals(new BigDecimal("0.3"), bdFromDoubleSum);

            // Exact calculation requires doing the addition purely in BigDecimal
            BigDecimal exactSum = new BigDecimal("0.1").add(new BigDecimal("0.2"));
            assertEquals(new BigDecimal("0.3"), exactSum);
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
        @DisplayName("OrderPriceCalculator executes exact total calculation and normalizes trailing zeros to scale 2")
        void calculatorValidCases() {
            // "19.99" -> scale 2
            BigDecimal total1 = OrderPriceCalculator.calculateTotalPrice("19.99", 3);
            assertEquals(new BigDecimal("59.97"), total1);
            assertEquals(2, total1.scale());

            // "10" -> scale 0 normalized to 10.00 -> 50.00
            BigDecimal total2 = OrderPriceCalculator.calculateTotalPrice("10", 5);
            assertEquals(new BigDecimal("50.00"), total2);
            assertEquals(2, total2.scale());

            // "10.000" -> scale 3 normalized to 10.00 without loss -> 20.00
            BigDecimal total3 = OrderPriceCalculator.calculateTotalPrice("10.000", 2);
            assertEquals(new BigDecimal("20.00"), total3);
            assertEquals(2, total3.scale());

            // "19.990" -> scale 3 normalized to 19.99 without loss -> 59.97
            BigDecimal total4 = OrderPriceCalculator.calculateTotalPrice("19.990", 3);
            assertEquals(new BigDecimal("59.97"), total4);
            assertEquals(2, total4.scale());

            // "0" -> normalized to 0.00 -> 0.00
            BigDecimal total5 = OrderPriceCalculator.calculateTotalPrice("0", 1);
            assertEquals(new BigDecimal("0.00"), total5);
            assertEquals(2, total5.scale());
        }

        @Test
        @DisplayName("OrderPriceCalculator rejects invalid inputs and inputs that require rounding to reach scale 2")
        void calculatorRejections() {
            // Negative price
            assertThatThrownBy(() -> OrderPriceCalculator.calculateTotalPrice("-5.00", 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unit price must be non-negative");

            // Non-positive quantity
            assertThatThrownBy(() -> OrderPriceCalculator.calculateTotalPrice("10.00", 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Quantity must be strictly positive");

            // Scale violation: "10.001" requires rounding to reach scale 2 -> must be rejected
            assertThatThrownBy(() -> OrderPriceCalculator.calculateTotalPrice("10.001", 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unit price cannot be represented exactly with scale 2 without rounding");

            // Scale violation: "0.001" unit price must be rejected before quantity multiplication
            assertThatThrownBy(() -> OrderPriceCalculator.calculateTotalPrice("0.001", 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unit price cannot be represented exactly with scale 2 without rounding");

            // Malformed number string
            assertThatThrownBy(() -> OrderPriceCalculator.calculateTotalPrice("abc", 2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid decimal price format");
        }
    }
}
