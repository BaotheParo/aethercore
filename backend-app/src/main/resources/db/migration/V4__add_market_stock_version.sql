-- Migration V4: Add version column to market_stock for optimistic locking and state evolution tracking
-- Enforces version >= 0 check constraint.

ALTER TABLE market_stock
ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0);