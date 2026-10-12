-- Test-only probe table for isolating transaction lifecycle observations
CREATE TABLE IF NOT EXISTS tx_probe (
    id UUID PRIMARY KEY,
    case_id VARCHAR(40) NOT NULL,
    phase VARCHAR(20) NOT NULL
);
