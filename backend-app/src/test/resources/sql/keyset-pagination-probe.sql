-- Test-only probe table for M2-E5B Keyset vs Offset Pagination Experiments
CREATE TABLE IF NOT EXISTS keyset_trade (
    id         BIGINT PRIMARY KEY,
    buyer_id   BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    payload    TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_kst_buyer_recent
    ON keyset_trade (buyer_id, created_at DESC, id DESC);
