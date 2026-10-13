-- Test-only probe table for M2-E5A PostgreSQL Query Plan & Index Experiments
CREATE TABLE IF NOT EXISTS query_plan_trade (
    id BIGINT PRIMARY KEY,
    buyer_id INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('PENDING', 'COMPLETED')),
    created_at TIMESTAMPTZ NOT NULL,
    payload TEXT NOT NULL
);
