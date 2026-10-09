-- ====================================================================
-- AetherCore Reference DDL (Documentation & Manual DBA Setup)
-- Note: In the application runtime, schema is managed via Flyway:
--       backend-app/src/main/resources/db/migration/V1__create_lab_entry_table.sql
-- ====================================================================

CREATE TABLE IF NOT EXISTS lab_entry (
    id VARCHAR(64) PRIMARY KEY,
    label VARCHAR(255) NOT NULL
);

INSERT INTO lab_entry (id, label) VALUES
    ('entry-01', 'AetherCore Baseline Initial Lab Entry'),
    ('entry-02', 'High-Throughput Synthetic Event Record'),
    ('entry-03', 'Diagnostic Verification Sample')
ON CONFLICT (id) DO NOTHING;
