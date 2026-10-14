package com.aethercore.backend.market.facade;

import com.aethercore.backend.market.http.PurchaseRequestQueryService;
import com.aethercore.backend.market.http.PurchaseRequestResultDto;
import com.aethercore.backend.market.idempotency.IdempotentMarketPurchaseService;
import com.aethercore.backend.market.idempotency.IdempotentPurchaseCommand;
import com.aethercore.backend.market.idempotency.IdempotentPurchaseOutcome;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Application Facade sitting between HTTP controllers and domain/JDBC services.
 * Enforces method authorization via Spring AOP proxy (@PreAuthorize) and extracts
 * trusted caller identity strictly from SecurityContextHolder.
 *
 * Does not declare outer @Transactional to preserve internal fine-grained transaction boundaries.
 */
@Service
public class MarketPurchaseFacade {

    private final IdempotentMarketPurchaseService purchaseService;
    private final PurchaseRequestQueryService queryService;

    public MarketPurchaseFacade(
            IdempotentMarketPurchaseService purchaseService,
            PurchaseRequestQueryService queryService
    ) {
        this.purchaseService = purchaseService;
        this.queryService = queryService;
    }

    /**
     * Executes purchase under the caller identity established in SecurityContext.
     */
    @PreAuthorize("hasRole('TRADER')")
    public IdempotentPurchaseOutcome purchase(UUID idempotencyKey, String sku, int quantity) {
        UUID callerId = extractCallerIdFromSecurityContext();
        IdempotentPurchaseCommand command = new IdempotentPurchaseCommand(
                callerId,
                idempotencyKey,
                sku,
                quantity
        );
        return purchaseService.purchase(command);
    }

    /**
     * Queries completed purchase request strictly scoped to the caller in SecurityContext.
     */
    @PreAuthorize("hasRole('TRADER')")
    public Optional<PurchaseRequestResultDto> findRequest(UUID idempotencyKey) {
        UUID callerId = extractCallerIdFromSecurityContext();
        return queryService.findCompletedPurchaseRequest(callerId, idempotencyKey);
    }

    private UUID extractCallerIdFromSecurityContext() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException(
                    "An authenticated principal is required to execute market operations"
            );
        }
        String name = authentication.getName();
        if (name == null || name.length() != 36) {
            throw new AuthenticationCredentialsNotFoundException(
                    "Subject principal must be a 36-character canonical UUID string: " + name
            );
        }
        try {
            UUID parsed = UUID.fromString(name);
            if (!parsed.toString().equalsIgnoreCase(name)) {
                throw new AuthenticationCredentialsNotFoundException(
                        "Subject principal is not a canonical UUID: " + name
                );
            }
            return parsed;
        } catch (IllegalArgumentException ex) {
            throw new AuthenticationCredentialsNotFoundException(
                    "Subject principal cannot be parsed as UUID: " + name,
                    ex
            );
        }
    }
}
