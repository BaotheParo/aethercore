package com.aethercore.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * M2-E1B: Optimistic Locking Purchase Service.
 * Implements optimistic concurrency control without holding row locks across reads:
 * <ol>
 *   <li>Reads stock snapshot (available, version) via plain SELECT (no lock).</li>
 *   <li>Performs application-level availability check.</li>
 *   <li>Executes conditional UPDATE matching the exact expected version:
 *       {@code WHERE sku = ? AND version = ? AND available >= ?}.</li>
 *   <li>If affected rows is 0, throws {@link StockVersionConflictException} to roll back the current transaction.</li>
 *   <li>Any retry must be executed by the caller in a new, unpolluted transaction.</li>
 * </ol>
 *
 * <p><b>Transaction Propagation & SUCCESS Contract:</b>
 * <ul>
 *   <li>The underlying {@link TransactionTemplate} uses default {@code PROPAGATION_REQUIRED}.</li>
 *   <li><b>When caller has no active transaction:</b> The template starts a new transaction,
 *       executes the optimistic conditional update and purchase insertion, and commits upon method exit.
 *       Returning {@link PurchaseResult#SUCCESS} indicates a durably committed purchase.</li>
 *   <li><b>When caller has an active transaction:</b> The service participates in the caller's
 *       existing transaction. Returning {@link PurchaseResult#SUCCESS} indicates that the optimistic
 *       update and purchase insert succeeded within the current transaction context. However,
 *       if the outer transaction subsequently encounters an error and rolls back, this purchase
 *       will also be rolled back as part of the outer transaction boundary.</li>
 *   <li><b>Isolation:</b> Configured with {@code ISOLATION_READ_COMMITTED}.</li>
 * </ul>
 */
@Service
public class OptimisticMarketPurchaseService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public OptimisticMarketPurchaseService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /**
     * Reads current snapshot without locking.
     */
    public Optional<StockSnapshot> findSnapshot(String sku) {
        if (sku == null || sku.isBlank()) {
            return Optional.empty();
        }
        List<StockSnapshot> results = jdbcTemplate.query(
                "SELECT sku, available, version FROM market_stock WHERE sku = ?",
                (rs, rowNum) -> new StockSnapshot(rs.getString("sku"), rs.getLong("available"), rs.getLong("version")),
                sku
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Attempts an optimistic purchase within a transaction.
     *
     * @param purchaseId Non-null unique purchase ID
     * @param sku Non-null, non-blank item SKU
     * @param quantity Positive integer (> 0)
     * @return {@link PurchaseResult#SUCCESS} if purchase recorded; {@link PurchaseResult#NOT_AVAILABLE} if insufficient stock or missing SKU
     * @throws StockVersionConflictException if a concurrent modification changed the stock version
     * @throws IllegalArgumentException if inputs are invalid
     */
    public PurchaseResult purchase(UUID purchaseId, String sku, int quantity) {
        validateInputs(purchaseId, sku, quantity);

        return transactionTemplate.execute(status -> {
            Optional<StockSnapshot> opt = findSnapshot(sku);
            if (opt.isEmpty()) {
                return PurchaseResult.NOT_AVAILABLE;
            }

            StockSnapshot snapshot = opt.get();
            onSnapshotRead(snapshot);

            if (snapshot.available() < quantity) {
                return PurchaseResult.NOT_AVAILABLE;
            }

            int updated = jdbcTemplate.update(
                    "UPDATE market_stock SET available = available - ?, version = version + 1 WHERE sku = ? AND version = ? AND available >= ?",
                    (long) quantity,
                    sku,
                    snapshot.version(),
                    (long) quantity
            );

            if (updated == 0) {
                // Version changed or stock became insufficient concurrently
                throw new StockVersionConflictException(sku, snapshot.version());
            }

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
     * Extension point invoked immediately after reading the snapshot within the active transaction.
     * Subclasses (e.g. test coordinators) may override this hook without altering production business logic.
     */
    protected void onSnapshotRead(StockSnapshot snapshot) {
        // Default: no-op in production
    }

    private void validateInputs(UUID purchaseId, String sku, int quantity) {
        if (purchaseId == null) {
            throw new IllegalArgumentException("purchaseId must not be null");
        }
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be null or blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be strictly positive (> 0)");
        }
    }
}