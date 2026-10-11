package com.aethercore.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * M2-E1A Broken Implementation (Test-only):
 * Intentionally performs stale read-then-write (lost update) using CyclicBarrier.
 * Demonstrates that having a transaction at READ COMMITTED does NOT protect against lost updates
 * if the write relies on an uncoordinated prior SELECT without FOR UPDATE.
 */
public class BrokenMarketPurchaseService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final CyclicBarrier barrier;

    public static class WorkerObservation {
        public final long backendPid;
        public final String isolation;
        public final long readAvailable;
        public final PurchaseResult result;

        public WorkerObservation(long backendPid, String isolation, long readAvailable, PurchaseResult result) {
            this.backendPid = backendPid;
            this.isolation = isolation;
            this.readAvailable = readAvailable;
            this.result = result;
        }
    }

    public BrokenMarketPurchaseService(JdbcTemplate jdbcTemplate,
                                       PlatformTransactionManager transactionManager,
                                       CyclicBarrier barrier) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.barrier = barrier;
    }

    public PurchaseResult purchase(UUID purchaseId, String sku, int quantity) {
        return purchaseWithObservation(purchaseId, sku, quantity, null);
    }

    public PurchaseResult purchaseWithObservation(UUID purchaseId, String sku, int quantity, java.util.function.Consumer<WorkerObservation> observer) {
        if (purchaseId == null || sku == null || quantity <= 0) {
            throw new IllegalArgumentException("Invalid purchase parameters");
        }

        return transactionTemplate.execute(status -> {
            // Record backend PID and isolation for verification
            Long backendPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Long.class);
            String isolation = jdbcTemplate.queryForObject("SHOW transaction_isolation", String.class);

            // 1. Stale read: SELECT available without FOR UPDATE
            Long current = jdbcTemplate.queryForObject(
                    "SELECT available FROM market_stock WHERE sku = ?",
                    Long.class,
                    sku
            );

            if (current == null || current < quantity) {
                if (observer != null && backendPid != null) {
                    observer.accept(new WorkerObservation(backendPid, isolation, current != null ? current : 0L, PurchaseResult.NOT_AVAILABLE));
                }
                return PurchaseResult.NOT_AVAILABLE;
            }

            // 2. Barrier synchronization: pause here until all participating threads have read the same stale value
            if (barrier != null) {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Worker thread interrupted while waiting at CyclicBarrier", e);
                } catch (BrokenBarrierException e) {
                    throw new RuntimeException("CyclicBarrier was broken by another worker", e);
                } catch (TimeoutException e) {
                    throw new RuntimeException("CyclicBarrier timed out waiting for second worker", e);
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

            if (observer != null && backendPid != null) {
                observer.accept(new WorkerObservation(backendPid, isolation, current, PurchaseResult.SUCCESS));
            }

            return PurchaseResult.SUCCESS;
        });
    }
}
