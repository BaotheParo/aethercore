package com.aethercore.backend.fetch;

/**
 * Read-only DTO projection for order history summary.
 * Designed for constructor expressions and safe detachment from Hibernate persistence context.
 */
public record OrderSummary(Long orderId, String buyerName, int quantity) {
}
