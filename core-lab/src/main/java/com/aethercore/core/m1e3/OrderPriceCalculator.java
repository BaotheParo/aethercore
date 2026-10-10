package com.aethercore.core.m1e3;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * OrderPriceCalculator (M1-E3 Precision & Decimal Helper):
 * Enforces strict financial calculation contract for lab exercises:
 * 1. String-based price input to avoid floating-point binary conversion errors.
 * 2. Non-negative price and positive integer quantity.
 * 3. Enforces max 2 decimal scale policy without silent rounding on input.
 * 4. Explicit RoundingMode support for division.
 */
public final class OrderPriceCalculator {

    private static final int MAX_INPUT_SCALE = 2;
    private static final int STANDARD_SCALE = 2;

    private OrderPriceCalculator() {
        // Utility class
    }

    /**
     * Calculates total order price: unitPrice * quantity
     *
     * @param unitPriceStr Decimal string representation of unit price (e.g. "19.99")
     * @param quantity     Positive integer quantity
     * @return Exact BigDecimal total with scale 2
     */
    public static BigDecimal calculateTotalPrice(String unitPriceStr, int quantity) {
        Objects.requireNonNull(unitPriceStr, "unitPriceStr must not be null");
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be strictly positive (got: " + quantity + ")");
        }

        BigDecimal unitPrice;
        try {
            unitPrice = new BigDecimal(unitPriceStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid decimal price format: " + unitPriceStr, e);
        }

        if (unitPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Unit price must be non-negative (got: " + unitPrice + ")");
        }

        // Check scale policy: reject inputs requiring rounding to reach 2 decimals
        if (unitPrice.scale() > MAX_INPUT_SCALE) {
            throw new IllegalArgumentException("Unit price exceeds maximum allowed scale of " + MAX_INPUT_SCALE + " (got scale: " + unitPrice.scale() + ")");
        }

        BigDecimal quantityBd = BigDecimal.valueOf(quantity);
        return unitPrice.multiply(quantityBd).setScale(STANDARD_SCALE, RoundingMode.UNNECESSARY);
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
