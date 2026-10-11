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
 *   <li>Caller or retry harness catches the conflict and retries in a new, independent transaction.</li>
 * </ol>
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
     * Attempts a single optimistic purchase.
     *
     * @param purchaseId Non-null unique purchase ID
     * @param sku Non-null, non-blank item SKU
     * @param quantity Positive integer (> 0)
     * @return {@link PurchaseResult#SUCCESS} if purchase recorded; {@link PurchaseResult#NOT_AVAILABLE} if insufficient stock
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
     * Executes purchase against an explicit expected snapshot (useful for deterministic concurrency testing).
     */
    public PurchaseResult purchaseWithExpectedSnapshot(UUID purchaseId, StockSnapshot snapshot, int quantity) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        validateInputs(purchaseId, snapshot.sku(), quantity);

        return transactionTemplate.execute(status -> {
            if (snapshot.available() < quantity) {
                return PurchaseResult.NOT_AVAILABLE;
            }

            int updated = jdbcTemplate.update(
                    "UPDATE market_stock SET available = available - ?, version = version + 1 WHERE sku = ? AND version = ? AND available >= ?",
                    (long) quantity,
                    snapshot.sku(),
                    snapshot.version(),
                    (long) quantity
            );

            if (updated == 0) {
                throw new StockVersionConflictException(snapshot.sku(), snapshot.version());
            }

            jdbcTemplate.update(
                    "INSERT INTO market_purchase (id, sku, quantity, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                    purchaseId,
                    snapshot.sku(),
                    (long) quantity
            );

            return PurchaseResult.SUCCESS;
        });
    }

    /**
     * Retries optimistic purchase in a fresh transaction upon {@link StockVersionConflictException}.
     */
    public PurchaseResult purchaseWithRetry(UUID purchaseId, String sku, int quantity, int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return purchase(purchaseId, sku, quantity);
            } catch (StockVersionConflictException ex) {
                if (attempt >= maxRetries) {
                    throw ex;
                }
                try {
                    // Exponential backoff with jitter to resolve optimistic retry storms under high contention
                    long backoffMs = Math.min(50L, (1L << Math.min(attempt, 5)) + java.util.concurrent.ThreadLocalRandom.current().nextInt(5));
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during optimistic retry backoff", ie);
                }
            }
        }
        throw new IllegalStateException("Unexpected exit from purchaseWithRetry loop");
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