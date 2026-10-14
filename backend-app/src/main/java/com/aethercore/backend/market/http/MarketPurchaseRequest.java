package com.aethercore.backend.market.http;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Inbound request body for POST /api/market/purchases.
 * Strictly limited to sku and quantity. Unknown fields are rejected with 400.
 */
public record MarketPurchaseRequest(
        @NotBlank(message = "sku must not be null or blank")
        @Size(max = 64, message = "sku length must not exceed 64")
        String sku,

        @NotNull(message = "quantity must not be null")
        @Positive(message = "quantity must be strictly positive (> 0)")
        Integer quantity
) {}
