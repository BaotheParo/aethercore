-- V1: Create lab_entry table for AetherCore minimal lab flow
CREATE TABLE IF NOT EXISTS lab_entry (
    id VARCHAR(64) PRIMARY KEY,
    label VARCHAR(255) NOT NULL
);
