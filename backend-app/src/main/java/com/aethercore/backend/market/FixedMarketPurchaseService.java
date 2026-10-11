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
    }

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
