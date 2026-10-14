package com.aethercore.backend.market.http;

import com.aethercore.backend.market.idempotency.IdempotentMarketPurchaseService;
import com.aethercore.backend.market.idempotency.IdempotentPurchaseCommand;
import com.aethercore.backend.market.idempotency.IdempotentPurchaseOutcome;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller exposing market purchase and purchase request query endpoints.
 * Caller identity is strictly extracted from verified JWT 'sub' claim.
 * No outer transaction is opened by controller.
 */
@RestController
@RequestMapping("/api/market")
public class MarketPurchaseController {

    private final IdempotentMarketPurchaseService purchaseService;
    private final PurchaseRequestQueryService queryService;

    public MarketPurchaseController(
            IdempotentMarketPurchaseService purchaseService,
            PurchaseRequestQueryService queryService
    ) {
        this.purchaseService = purchaseService;
        this.queryService = queryService;
    }

    @PostMapping("/purchases")
    public ResponseEntity<MarketPurchaseResponse> purchase(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody MarketPurchaseRequest request
    ) {
        if (idempotencyKeyHeader == null || idempotencyKeyHeader.trim().isEmpty()) {
            throw new IllegalArgumentException("Header 'Idempotency-Key' is required");
        }
        UUID idempotencyKey;
        try {
            idempotencyKey = UUID.fromString(idempotencyKeyHeader.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Header 'Idempotency-Key' must be a valid canonical UUID: " + idempotencyKeyHeader);
        }

        UUID callerId = UUID.fromString(jwt.getSubject());
        IdempotentPurchaseCommand command = new IdempotentPurchaseCommand(
                callerId,
                idempotencyKey,
                request.sku(),
                request.quantity()
        );

        IdempotentPurchaseOutcome outcome = purchaseService.purchase(command);
        return ResponseEntity.ok(new MarketPurchaseResponse(
                outcome.result().name(),
                outcome.purchaseId(),
                outcome.replayed()
        ));
    }

    @GetMapping("/purchase-requests/{idempotencyKey}")
    public ResponseEntity<PurchaseRequestResultDto> getPurchaseRequest(
            @PathVariable("idempotencyKey") String keyStr,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID idempotencyKey;
        try {
            idempotencyKey = UUID.fromString(keyStr.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Path variable 'idempotencyKey' must be a valid canonical UUID: " + keyStr);
        }

        UUID callerId = UUID.fromString(jwt.getSubject());
        return queryService.findCompletedPurchaseRequest(callerId, idempotencyKey)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new PurchaseRequestNotFoundException(
                        "Completed purchase request not found for key '" + idempotencyKey + "' under caller '" + callerId + "'"
                ));
    }
}
