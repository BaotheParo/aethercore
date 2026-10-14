package com.aethercore.backend.market.http;

import java.util.UUID;

/**
 * Query response DTO for GET /api/market/purchase-requests/{idempotencyKey}.
 */
public record PurchaseRequestResultDto(
        UUID idempotencyKey,
        UUID callerId,
        String operation,
        String state,
        String resultCode,
        UUID purchaseId,
        String sku,
        int quantity
) {}
