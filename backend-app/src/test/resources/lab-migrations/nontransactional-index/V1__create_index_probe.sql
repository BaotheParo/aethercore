CREATE TABLE index_probe (
    id BIGINT PRIMARY KEY,
    token VARCHAR(64) NOT NULL
);

INSERT INTO index_probe (id, token) VALUES
    (1, 'IRON'), (2, 'IRON'), (3, 'GOLD');
