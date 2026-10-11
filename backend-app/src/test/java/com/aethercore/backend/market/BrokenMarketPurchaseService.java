package com.aethercore.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

/**
 * M2-E1A Broken Implementation (Test-only):
 * Intentionally performs stale read-then-write (lost update) using CyclicBarrier.
 * Demonstrates that having a transaction at READ COMMITTED does NOT protect against lost updates
 * if the write relies on an uncoordinated prior SELECT.
 */
public class BrokenMarketPurchaseService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final CyclicBarrier barrier;

    public BrokenMarketPurchaseService(JdbcTemplate jdbcTemplate,
                                       PlatformTransactionManager transactionManager,
                                       CyclicBarrier barrier) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.barrier = barrier;
    }

    public PurchaseResult purchase(UUID purchaseId, String sku, int quantity) {
        if (purchaseId == null || sku == null || quantity <= 0) {
            throw new IllegalArgumentException("Invalid purchase parameters");
        }

        return transactionTemplate.execute(status -> {
            // 1. Stale read: SELECT available without FOR UPDATE
            Long current = jdbcTemplate.queryForObject(
                    "SELECT available FROM market_stock WHERE sku = ?",
                    Long.class,
                    sku
            );

            if (current == null || current < quantity) {
                return PurchaseResult.NOT_AVAILABLE;
            }

            // 2. Barrier synchronization: pause here until all participating threads have read the same stale value
            if (barrier != null) {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException("CyclicBarrier interrupted or broken", e);
                }
            }

            // 3. Stale calculation in Java: absolute overwrite based on previously read snapshot
            long newAvailable = current - quantity;
            jdbcTemplate.update(
                    "UPDATE market_stock SET available = ? WHERE sku = ?",
                    newAvailable,
                    sku
            );

            // 4. Insert purchase record in the same transaction
            jdbcTemplate.update(
                    "INSERT INTO market_purchase (id, sku, quantity, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                    purchaseId,
                    sku,
                    (long) quantity
            );

            return PurchaseResult.SUCCESS;
        });
    }
}
