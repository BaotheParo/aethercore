-- DDL for M2-E4A: JPA Fetching, N+1, JOIN FETCH, EntityGraph, and DTO Projection experiments
CREATE TABLE IF NOT EXISTS fetch_buyer (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS fetch_order (
    id BIGINT PRIMARY KEY,
    buyer_id BIGINT NOT NULL REFERENCES fetch_buyer(id),
    quantity INTEGER NOT NULL CHECK (quantity > 0)
);
