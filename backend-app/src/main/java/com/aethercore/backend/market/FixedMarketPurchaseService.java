package com.aethercore.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;

/**
 * M2-E1A Fixed Implementation:
 * Uses atomic conditional UPDATE in a single READ COMMITTED transaction with TransactionTemplate.
 * Prevents lost updates without relying on application-level locks.
 *
 * <p><b>Transaction Propagation & SUCCESS Contract:</b>
 * <ul>
 *   <li>The underlying {@link TransactionTemplate} uses default {@code PROPAGATION_REQUIRED}.</li>
 *   <li><b>When caller has no active transaction:</b> The template starts a new transaction,
 *       executes the atomic decrement and purchase insertion, and commits upon method exit.
 *       Returning {@link PurchaseResult#SUCCESS} indicates a durably committed purchase.</li>
 *   <li><b>When caller has an active transaction:</b> The service participates in the caller's
 *       existing transaction. Returning {@link PurchaseResult#SUCCESS} indicates that the conditional
 *       decrement and purchase insert succeeded within the current transaction context. However,
 *       if the outer transaction subsequently encounters an error and rolls back, this purchase
 *       will also be rolled back as part of the outer transaction boundary.</li>
 *   <li><b>Isolation:</b> Configured with {@code ISOLATION_READ_COMMITTED}. Note that when participating
 *       in an existing outer transaction, Spring's transaction manager does not change the isolation
 *       level of the pre-existing outer transaction.</li>
 * </ul>
 */
@Service
public class FixedMarketPurchaseService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public FixedMarketPurchaseService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /**
     * Attempts to purchase a given quantity of an item identified by SKU.
     *
     * @param purchaseId Non-null unique purchase ID (UUID)
     * @param sku Non-null, non-blank item SKU
     * @param quantity Positive integer (> 0)
     * @return {@link PurchaseResult#SUCCESS} if stock was decremented and purchase recorded;
     *         {@link PurchaseResult#NOT_AVAILABLE} if stock was insufficient or SKU missing
     * @throws IllegalArgumentException if parameters are invalid
     * @throws IllegalStateException if unexpected number of rows affected
     */
    public PurchaseResult purchase(UUID purchaseId, String sku, int quantity) {
        if (purchaseId == null) {
            throw new IllegalArgumentException("purchaseId must not be null");
        }
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be null or blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be strictly positive (> 0)");
        }

        return transactionTemplate.execute(status -> {
            // Atomic conditional update: decreases available stock only if current available >= quantity
            int updated = jdbcTemplate.update(
                    "UPDATE market_stock SET available = available - ? WHERE sku = ? AND available >= ?",
                    (long) quantity,
                    sku,
                    (long) quantity
            );

            if (updated == 0) {
                // Stock was either insufficient or SKU does not exist
                return PurchaseResult.NOT_AVAILABLE;
            } else if (updated == 1) {
                // Decrement succeeded; record purchase record in the exact same transaction
                jdbcTemplate.update(
                        "INSERT INTO market_purchase (id, sku, quantity, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                        purchaseId,
                        sku,
                        (long) quantity
                );
                return PurchaseResult.SUCCESS;
            } else {
                throw new IllegalStateException("Invariant violated: expected 0 or 1 rows affected by SKU update, got " + updated);
            }
        });
    }
}
