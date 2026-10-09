package com.aethercore.backend.repository;

import com.aethercore.backend.model.LabEntry;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository using Spring JdbcTemplate for minimal, explicit SQL execution.
 * Reason for choosing JdbcTemplate:
 * 1. Zero ORM magic: Clear mapping from HTTP request -> Controller -> SQL -> PostgreSQL.
 * 2. High predictability: Deterministic connection usage and explicit query execution.
 * 3. Perfect for learning: Direct visibility into PreparedStatement and ResultSet mechanics.
 */
@Repository
public class LabEntryRepository {

    private final JdbcTemplate jdbcTemplate;

    // Explicit Constructor Injection
    public LabEntryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<LabEntry> findById(String id) {
        String sql = "SELECT id, label FROM lab_entry WHERE id = ?";
        try {
            LabEntry entry = jdbcTemplate.queryForObject(
                sql,
                (rs, rowNum) -> new LabEntry(rs.getString("id"), rs.getString("label")),
                id
            );
            return Optional.ofNullable(entry);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
