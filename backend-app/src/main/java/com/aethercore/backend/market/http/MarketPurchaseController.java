package com.aethercore.backend.market.http;

import com.aethercore.backend.market.facade.MarketPurchaseFacade;
import com.aethercore.backend.market.idempotency.IdempotentPurchaseOutcome;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller exposing market purchase and purchase request query endpoints.
 * Validates HTTP transport input and delegates to MarketPurchaseFacade.
 * Caller identity is extracted from SecurityContext by the facade; HTTP parameters
 * never specify callerId.
 */
@RestController
@RequestMapping("/api/market")
public class MarketPurchaseController {

    private final MarketPurchaseFacade facade;

    public MarketPurchaseController(MarketPurchaseFacade facade) {
        this.facade = facade;
    }

    @PostMapping("/purchases")
    public ResponseEntity<MarketPurchaseResponse> purchase(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Valid @RequestBody MarketPurchaseRequest request
    ) {
        if (idempotencyKeyHeader == null || idempotencyKeyHeader.trim().isEmpty()) {
            throw new InvalidRequestException("Header 'Idempotency-Key' is required");
        }
        UUID idempotencyKey = parseCanonicalUuid(idempotencyKeyHeader.trim(), "Header 'Idempotency-Key'");

        IdempotentPurchaseOutcome outcome = facade.purchase(
                idempotencyKey,
                request.sku(),
                request.quantity()
        );

        return ResponseEntity.ok(new MarketPurchaseResponse(
                outcome.result().name(),
                outcome.purchaseId(),
                outcome.replayed()
        ));
    }

    @GetMapping("/purchase-requests/{idempotencyKey}")
    public ResponseEntity<PurchaseRequestResultDto> getPurchaseRequest(
            @PathVariable("idempotencyKey") String keyStr
    ) {
        if (keyStr == null || keyStr.trim().isEmpty()) {
            throw new InvalidRequestException("Path variable 'idempotencyKey' is required");
        }
        UUID idempotencyKey = parseCanonicalUuid(keyStr.trim(), "Path variable 'idempotencyKey'");

        return facade.findRequest(idempotencyKey)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new PurchaseRequestNotFoundException(
                        "Completed purchase request not found for key '" + idempotencyKey + "'"
                ));
    }

    public static UUID parseCanonicalUuid(String value, String parameterName) {
        if (value == null || value.length() != 36) {
            throw new InvalidRequestException(parameterName + " must be a 36-character canonical UUID string: " + value);
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) {
                throw new InvalidRequestException(parameterName + " must be a canonical UUID string: " + value);
            }
            return parsed;
        } catch (IllegalArgumentException ex) {
            throw new InvalidRequestException(parameterName + " must be a valid canonical UUID: " + value, ex);
        }
    }
}
