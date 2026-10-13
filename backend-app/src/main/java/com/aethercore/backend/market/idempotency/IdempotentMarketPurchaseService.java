package com.aethercore.backend.market.idempotency;

import com.aethercore.backend.market.FixedMarketPurchaseService;
import com.aethercore.backend.market.PurchaseResult;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * M2-E6A: Transactional Idempotency for Market Purchases.
 *
 * <p><b>Transaction Contract:</b>
 * <ul>
 *   <li>Uses {@link TransactionTemplate} with {@code PROPAGATION_REQUIRED} and {@code ISOLATION_READ_COMMITTED}.</li>
 *   <li>When no outer transaction exists, executes in an autonomous transaction and commits upon method completion.</li>
 *   <li>When participating in an existing outer transaction, operates within that transaction's boundary.
 *       Verifies that the actual active transaction connection is configured with {@code READ_COMMITTED};
 *       rejects other isolation levels with a contract exception.</li>
 * </ul>
 *
 * <p><b>Claim and Replay Algorithm:</b>
 * <ol>
 *   <li>Attempts atomic reservation via {@code INSERT ... ON CONFLICT (caller_id, operation, idempotency_key) DO NOTHING}.</li>
 *   <li>If claim succeeds (1 row inserted): executes {@link FixedMarketPurchaseService#purchase(UUID, String, int)}
 *       in the same transaction, records outcome as {@code COMPLETED}, and returns the fresh business outcome.</li>
 *   <li>If claim conflicts (0 rows inserted): executes a new SQL statement to load the stored outcome.
 *       Verifies exact payload and fingerprint matching (throwing {@link IdempotencyConflictException} on mismatch)
 *       and replaying the stored business outcome.</li>
 * </ol>
 */
@Service
public class IdempotentMarketPurchaseService {

    private final JdbcTemplate jdbcTemplate;
    private final FixedMarketPurchaseService fixedPurchaseService;
    private final TransactionTemplate transactionTemplate;

    public IdempotentMarketPurchaseService(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            FixedMarketPurchaseService fixedPurchaseService
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.fixedPurchaseService = Objects.requireNonNull(fixedPurchaseService, "fixedPurchaseService must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /**
     * Executes an idempotent purchase command.
     *
     * @param command Validated idempotent purchase command
     * @return Idempotent business outcome (with {@code replayed} flag indicating whether it was replayed)
     * @throws IdempotencyConflictException if the key was previously used with a different payload or fingerprint
     * @throws IllegalStateException if an unexpected transaction isolation or database invariant violation occurs
     */
    public IdempotentPurchaseOutcome purchase(IdempotentPurchaseCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String fingerprint = RequestFingerprint.compute(command.sku(), command.quantity());

        return transactionTemplate.execute(status -> {
            // Guard: verify actual connection isolation level participating in current transaction
            Integer actualIsolation = jdbcTemplate.execute((ConnectionCallback<Integer>) Connection::getTransactionIsolation);
            if (actualIsolation == null || actualIsolation != Connection.TRANSACTION_READ_COMMITTED) {
                throw new IllegalStateException(
                        "Idempotent purchase requires READ_COMMITTED transaction isolation level, but actual connection level was " + actualIsolation
                );
            }

            // Step 1: Claim reservation with state = PROCESSING
            int inserted = jdbcTemplate.update(
                    "INSERT INTO market_idempotency (" +
                    "  caller_id, operation, idempotency_key, " +
                    "  request_sku, request_quantity, request_fingerprint, " +
                    "  state, result_code, purchase_id, created_at" +
                    ") VALUES (?, ?, ?, ?, ?, ?, 'PROCESSING', NULL, NULL, CURRENT_TIMESTAMP) " +
                    "ON CONFLICT (caller_id, operation, idempotency_key) DO NOTHING",
                    command.callerId(),
                    IdempotentPurchaseCommand.OPERATION,
                    command.idempotencyKey(),
                    command.sku(),
                    command.quantity(),
                    fingerprint
            );

            if (inserted == 1) {
                // Claim acquired: generate unique purchase ID and execute core purchase logic
                UUID purchaseId = UUID.randomUUID();
                PurchaseResult purchaseResult = fixedPurchaseService.purchase(purchaseId, command.sku(), command.quantity());

                UUID recordedPurchaseId = (purchaseResult == PurchaseResult.SUCCESS) ? purchaseId : null;

                // Step 2: Transition claim to COMPLETED with outcome in the exact same transaction
                int updated = jdbcTemplate.update(
                        "UPDATE market_idempotency " +
                        "SET state = 'COMPLETED', result_code = ?, purchase_id = ? " +
                        "WHERE caller_id = ? AND operation = ? AND idempotency_key = ? AND state = 'PROCESSING'",
                        purchaseResult.name(),
                        recordedPurchaseId,
                        command.callerId(),
                        IdempotentPurchaseCommand.OPERATION,
                        command.idempotencyKey()
                );

                if (updated != 1) {
                    throw new IllegalStateException(
                            "Invariant violated: expected exactly 1 owned claim row updated to COMPLETED, got " + updated
                    );
                }

                return new IdempotentPurchaseOutcome(purchaseResult, recordedPurchaseId, false);
            } else if (inserted == 0) {
                // Step 3: Conflict encountered -> query stored record using a new SQL statement
                List<StoredRecord> records = jdbcTemplate.query(
                        "SELECT request_sku, request_quantity, request_fingerprint, state, result_code, purchase_id " +
                        "FROM market_idempotency " +
                        "WHERE caller_id = ? AND operation = ? AND idempotency_key = ?",
                        (rs, rowNum) -> new StoredRecord(
                                rs.getString("request_sku"),
                                rs.getInt("request_quantity"),
                                rs.getString("request_fingerprint"),
                                rs.getString("state"),
                                rs.getString("result_code"),
                                rs.getObject("purchase_id", UUID.class)
                        ),
                        command.callerId(),
                        IdempotentPurchaseCommand.OPERATION,
                        command.idempotencyKey()
                );

                if (records.isEmpty()) {
                    throw new IllegalStateException(
                            "Invariant violated: ON CONFLICT occurred but stored idempotency record not found for (" +
                            command.callerId() + ", " + command.idempotencyKey() + ")"
                    );
                }

                StoredRecord stored = records.get(0);

                // Verify exact payload and fingerprint matching
                if (!Objects.equals(stored.sku(), command.sku()) ||
                        stored.quantity() != command.quantity() ||
                        !Objects.equals(stored.fingerprint(), fingerprint)) {
                    throw new IdempotencyConflictException(
                            "Idempotency key reuse conflict: command payload (sku=" + command.sku() +
                            ", qty=" + command.quantity() + ", fingerprint=" + fingerprint +
                            ") does not match stored request (sku=" + stored.sku() +
                            ", qty=" + stored.quantity() + ", fingerprint=" + stored.fingerprint() +
                            ") for key " + command.idempotencyKey()
                    );
                }

                // Verify completion invariant
                if (!"COMPLETED".equals(stored.state())) {
                    throw new IllegalStateException(
                            "Invariant violated: stored idempotency record is in uncompleted state '" +
                            stored.state() + "' for key " + command.idempotencyKey()
                    );
                }

                if (stored.resultCode() == null) {
                    throw new IllegalStateException(
                            "Invariant violated: stored result_code is null for COMPLETED state on key " + command.idempotencyKey()
                    );
                }

                PurchaseResult storedResult = PurchaseResult.valueOf(stored.resultCode());
                return new IdempotentPurchaseOutcome(storedResult, stored.purchaseId(), true);
            } else {
                throw new IllegalStateException(
                        "Invariant violated: expected 0 or 1 rows affected by idempotency claim INSERT, got " + inserted
                );
            }
        });
    }

    private record StoredRecord(
            String sku,
            int quantity,
            String fingerprint,
            String state,
            String resultCode,
            UUID purchaseId
    ) {}
}