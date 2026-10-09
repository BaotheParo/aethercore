-- Day 1 Minimal Schema
CREATE TABLE IF NOT EXISTS lab_entry (
    id VARCHAR(64) PRIMARY KEY,
    label VARCHAR(255) NOT NULL
);
