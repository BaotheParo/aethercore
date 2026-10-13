-- Test-only probe table for M2-E5C Predicate & Expression Index Experiments
CREATE TABLE IF NOT EXISTS predicate_trade (
    id           BIGINT PRIMARY KEY,
    display_name TEXT COLLATE "C" NOT NULL,
    payload      TEXT NOT NULL
);

-- Note: Secondary indexes idx_pt_name and idx_pt_lower_name are managed dynamically per experiment scenario:
-- CREATE INDEX idx_pt_name ON predicate_trade (display_name);
-- CREATE INDEX idx_pt_lower_name ON predicate_trade (lower(display_name));
