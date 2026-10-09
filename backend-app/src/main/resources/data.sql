-- Seed Sample Data for Learning/Testing Environment
INSERT INTO lab_entry (id, label) 
VALUES ('entry-01', 'AetherCore Baseline Initial Lab Entry')
ON CONFLICT (id) DO NOTHING;

INSERT INTO lab_entry (id, label) 
VALUES ('entry-02', 'Java Core Memory Benchmark Node')
ON CONFLICT (id) DO NOTHING;
