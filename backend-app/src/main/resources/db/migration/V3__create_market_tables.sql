-- Migration V3: Create Market Stock and Market Purchase Tables
-- Enforces baseline check constraint available >= 0 to demonstrate that CHECK constraints
-- do not prevent application-level lost updates on stale reads.

CREATE TABLE IF NOT EXISTS market_stock (
    sku VARCHAR(64) PRIMARY KEY,
    available BIGINT NOT NULL CHECK (available >= 0)
);

CREATE TABLE IF NOT EXISTS market_purchase (
    id UUID PRIMARY KEY,
    sku VARCHAR(64) NOT NULL REFERENCES market_stock(sku),
    quantity BIGINT NOT NULL CHECK (quantity > 0),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);
