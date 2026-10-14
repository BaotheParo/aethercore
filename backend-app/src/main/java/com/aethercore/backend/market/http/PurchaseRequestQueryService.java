package com.aethercore.backend.market.http;

import com.aethercore.backend.market.idempotency.IdempotentPurchaseCommand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Query service reading COMPLETED purchase-request results from market_idempotency table.
 * Strictly scoped by caller_id, operation, and idempotency_key in SQL.
 */
@Service
public class PurchaseRequestQueryService {

    private final JdbcTemplate jdbcTemplate;

    public PurchaseRequestQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<PurchaseRequestResultDto> findCompletedPurchaseRequest(UUID callerId, UUID idempotencyKey) {
        List<PurchaseRequestResultDto> results = jdbcTemplate.query(
                "SELECT idempotency_key, caller_id, operation, state, result_code, purchase_id, request_sku, request_quantity " +
                "FROM market_idempotency " +
                "WHERE caller_id = ? AND operation = ? AND idempotency_key = ? AND state = 'COMPLETED'",
                (rs, rowNum) -> new PurchaseRequestResultDto(
                        rs.getObject("idempotency_key", UUID.class),
                        rs.getObject("caller_id", UUID.class),
                        rs.getString("operation"),
                        rs.getString("state"),
                        rs.getString("result_code"),
                        rs.getObject("purchase_id", UUID.class),
                        rs.getString("request_sku"),
                        rs.getInt("request_quantity")
                ),
                callerId,
                IdempotentPurchaseCommand.OPERATION,
                idempotencyKey
        );

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
