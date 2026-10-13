-- Migration V5: Create Market Idempotency Table (M2-E6A)
-- Enforces composite primary key on (caller_id, operation, idempotency_key),
-- canonical request payload and SHA-256 fingerprint, state machine transitions,
-- and strict business outcome shape constraints.

CREATE TABLE IF NOT EXISTS market_idempotency (
    caller_id UUID NOT NULL,
    operation VARCHAR(32) NOT NULL,
    idempotency_key UUID NOT NULL,
    request_sku VARCHAR(64) NOT NULL,
    request_quantity INT NOT NULL CHECK (request_quantity > 0),
    request_fingerprint VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PROCESSING', 'COMPLETED')),
    result_code VARCHAR(16) CHECK (result_code IN ('SUCCESS', 'NOT_AVAILABLE')),
    purchase_id UUID REFERENCES market_purchase(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (caller_id, operation, idempotency_key),
    CONSTRAINT chk_idempotency_operation CHECK (operation = 'PURCHASE_V1'),
    CONSTRAINT chk_idempotency_state_shape CHECK (
        (state = 'PROCESSING' AND result_code IS NULL AND purchase_id IS NULL)
        OR
        (state = 'COMPLETED' AND (
            (result_code = 'SUCCESS' AND purchase_id IS NOT NULL)
            OR
            (result_code = 'NOT_AVAILABLE' AND purchase_id IS NULL)
        ))
    )
);