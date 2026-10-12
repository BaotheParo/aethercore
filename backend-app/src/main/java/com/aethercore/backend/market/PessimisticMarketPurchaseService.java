package com.aethercore.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * M2-E1B: Pessimistic Locking Purchase Service.
 * Implements pessimistic concurrency control by acquiring an exclusive row lock:
 * <ol>
 *   <li>Executes {@code SELECT sku, available, version FROM market_stock WHERE sku = ? FOR UPDATE}
 *       inside an active transaction.</li>
 *   <li>Other transactions attempting to select FOR UPDATE or UPDATE this row will block until
 *       this transaction commits or rolls back (observable via {@code pg_blocking_pids()}).</li>
 *   <li>Checks stock sufficiency. If insufficient, returns {@link PurchaseResult#NOT_AVAILABLE}.</li>
 *   <li>Updates stock and increments version: {@code UPDATE market_stock SET available = available - ?, version = version + 1 WHERE sku = ?}.</li>
 *   <li>Inserts purchase record into {@code market_purchase}.</li>
 *   <li>Returns {@link PurchaseResult#SUCCESS} upon commit.</li>
 * </ol>
 *
 * <p><b>Transaction Propagation & SUCCESS Contract:</b>
 * <ul>
 *   <li>The underlying {@link TransactionTemplate} uses default {@code PROPAGATION_REQUIRED}.</li>
 *   <li><b>When caller has no active transaction:</b> The template starts a new transaction,
 *       executes the pessimistic row lock, decrement, and purchase insertion, and commits upon method exit.
 *       Returning {@link PurchaseResult#SUCCESS} indicates a durably committed purchase.</li>
 *   <li><b>When caller has an active transaction:</b> The service participates in the caller's
 *       existing transaction. Returning {@link PurchaseResult#SUCCESS} indicates that the row lock,
 *       decrement, and purchase insert succeeded within the current transaction context. However,
 *       if the outer transaction subsequently encounters an error and rolls back, this purchase
 *       and row lock release will also be rolled back as part of the outer transaction boundary.</li>
 *   <li><b>Isolation:</b> Configured with {@code ISOLATION_READ_COMMITTED}.</li>
 * </ul>
 */
@Service
public class PessimisticMarketPurchaseService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public PessimisticMarketPurchaseService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /**
     * Executes purchase using pessimistic SELECT ... FOR UPDATE row locking.
     *
     * @param purchaseId Non-null unique purchase ID
     * @param sku Non-null, non-blank item SKU
     * @param quantity Positive integer (> 0)
     * @return {@link PurchaseResult#SUCCESS} if purchase recorded; {@link PurchaseResult#NOT_AVAILABLE} if insufficient stock or missing SKU
     * @throws IllegalArgumentException if inputs are invalid
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
            // 1. Acquire pessimistic row lock
            List<StockSnapshot> rows = jdbcTemplate.query(
                    "SELECT sku, available, version FROM market_stock WHERE sku = ? FOR UPDATE",
                    (rs, rowNum) -> new StockSnapshot(rs.getString("sku"), rs.getLong("available"), rs.getLong("version")),
                    sku
            );

            if (rows.isEmpty()) {
                return PurchaseResult.NOT_AVAILABLE;
            }

            StockSnapshot locked = rows.get(0);
            onLockAcquired(locked);

            if (locked.available() < quantity) {
                return PurchaseResult.NOT_AVAILABLE;
            }

            // 2. Decrement available and increment version
            int updated = jdbcTemplate.update(
                    "UPDATE market_stock SET available = available - ?, version = version + 1 WHERE sku = ?",
                    (long) quantity,
                    sku
            );

            if (updated != 1) {
                throw new IllegalStateException("Expected 1 row updated for SKU " + sku + ", got " + updated);
            }

            // 3. Record purchase
            jdbcTemplate.update(
                    "INSERT INTO market_purchase (id, sku, quantity, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                    purchaseId,
                    sku,
                    (long) quantity
            );

            return PurchaseResult.SUCCESS;
        });
    }

    /**
     * Extension point invoked immediately after acquiring the exclusive row lock inside the transaction.
     * Subclasses (e.g. test coordinators) may override this hook to observe blocking PIDs without altering production logic.
     */
    protected void onLockAcquired(StockSnapshot locked) {
        // Default: no-op in production
    }
}