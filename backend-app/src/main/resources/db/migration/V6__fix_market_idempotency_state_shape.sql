-- Migration V6: Fix market_idempotency state shape constraint (M2-E7A)
-- Replaces chk_idempotency_state_shape with a strict condition that closes
-- the NULL loophole in the COMPLETED branch.

ALTER TABLE market_idempotency
    DROP CONSTRAINT chk_idempotency_state_shape;

ALTER TABLE market_idempotency
    ADD CONSTRAINT chk_idempotency_state_shape CHECK (
        (state = 'PROCESSING'
            AND result_code IS NULL
            AND purchase_id IS NULL)
        OR
        (state = 'COMPLETED'
            AND result_code IS NOT NULL
            AND (
                (result_code = 'SUCCESS' AND purchase_id IS NOT NULL)
                OR
                (result_code = 'NOT_AVAILABLE' AND purchase_id IS NULL)
            ))
    );
