package com.aethercore.backend.market.http;

import java.util.UUID;

/**
 * Standard HTTP response body for POST /api/market/purchases.
 */
public record MarketPurchaseResponse(
        String resultCode,
        UUID purchaseId,
        boolean replayed
) {}
