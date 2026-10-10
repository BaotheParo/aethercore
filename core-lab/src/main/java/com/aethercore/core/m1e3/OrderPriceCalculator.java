package com.aethercore.core.m1e3;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * OrderPriceCalculator (M1-E3 Precision & Decimal Helper):
 * Enforces strict financial calculation contract for lab exercises:
 * 1. String-based price input to avoid floating-point binary conversion errors.
 * 2. Non-negative price and strictly positive integer quantity.
 * 3. Normalizes unit price to exact scale 2 with RoundingMode.UNNECESSARY.
 *    Rejects inputs that require rounding to reach scale 2 (e.g. "10.001" is rejected, while "10.000" is normalized to 10.00).
 * 4. Explicit RoundingMode support for division.
 */
public final class OrderPriceCalculator {

    private static final int STANDARD_SCALE = 2;

    private OrderPriceCalculator() {
        // Utility class
    }

    /**
     * Calculates total order price: normalizedUnitPrice * quantity
     *
     * @param unitPriceStr Decimal string representation of unit price (e.g. "19.99", "10.000")
     * @param quantity     Positive integer quantity
     * @return Exact BigDecimal total with scale 2
     */
    public static BigDecimal calculateTotalPrice(String unitPriceStr, int quantity) {
        Objects.requireNonNull(unitPriceStr, "unitPriceStr must not be null");
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be strictly positive (got: " + quantity + ")");
        }

        BigDecimal rawUnitPrice;
        try {
            rawUnitPrice = new BigDecimal(unitPriceStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid decimal price format: " + unitPriceStr, e);
        }

        if (rawUnitPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Unit price must be non-negative (got: " + rawUnitPrice + ")");
        }

        // Normalize unit price to scale 2 without allowing lossy rounding
        BigDecimal normalizedUnitPrice;
        try {
            normalizedUnitPrice = rawUnitPrice.setScale(STANDARD_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                "Unit price cannot be represented exactly with scale " + STANDARD_SCALE + " without rounding (got: " + unitPriceStr + ")",
                e
            );
        }

        BigDecimal quantityBd = BigDecimal.valueOf(quantity);
        return normalizedUnitPrice.multiply(quantityBd).setScale(STANDARD_SCALE, RoundingMode.UNNECESSARY);
    }

    /**
     * Divides an amount equally into parts with explicit scale and rounding mode.
     */
    public static BigDecimal divideEqually(BigDecimal totalAmount, int parts, int scale, RoundingMode roundingMode) {
        Objects.requireNonNull(totalAmount, "totalAmount must not be null");
        Objects.requireNonNull(roundingMode, "roundingMode must not be null");
        if (parts <= 0) {
            throw new IllegalArgumentException("Parts must be strictly positive (got: " + parts + ")");
        }
        return totalAmount.divide(BigDecimal.valueOf(parts), scale, roundingMode);
    }
}
