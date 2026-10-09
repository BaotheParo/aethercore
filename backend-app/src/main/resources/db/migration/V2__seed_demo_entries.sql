-- V2: Seed baseline dataset for lab testing and verification
INSERT INTO lab_entry (id, label) VALUES
    ('entry-01', 'AetherCore Baseline Initial Lab Entry'),
    ('entry-02', 'High-Throughput Synthetic Event Record'),
    ('entry-03', 'Diagnostic Verification Sample')
ON CONFLICT (id) DO NOTHING;
