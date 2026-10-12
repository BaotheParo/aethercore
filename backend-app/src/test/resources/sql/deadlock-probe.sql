-- Test-only probe table for M2-E2A PostgreSQL Deadlock & Ordering Experiments
CREATE TABLE IF NOT EXISTS deadlock_counter (
    id INTEGER PRIMARY KEY,
    value BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS deadlock_operation (
    operation_id UUID PRIMARY KEY
);
