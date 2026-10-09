package com.aethercore.backend.controller;

import com.aethercore.backend.model.LabEntry;
import com.aethercore.backend.repository.LabEntryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Controller exposing minimal Lab Entry query endpoint.
 * Request Flow:
 * Client HTTP GET /api/lab/entries/{id}
 *   -> DispatcherServlet
 *   -> LabEntryController.getEntry(id)
 *   -> LabEntryRepository.findById(id)
 *   -> JdbcTemplate -> Hikari Connection -> PostgreSQL SELECT
 *   -> JSON ResponseEntity (200 OK or 404 NOT_FOUND)
 */
@RestController
@RequestMapping("/api/lab/entries")
public class LabEntryController {

    private final LabEntryRepository labEntryRepository;

    // Explicit Constructor Injection
    public LabEntryController(LabEntryRepository labEntryRepository) {
        this.labEntryRepository = labEntryRepository;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getEntry(@PathVariable("id") String id) {
        return labEntryRepository.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        Map.of(
                                "error", "NOT_FOUND",
                                "message", "Lab entry with id '" + id + "' does not exist",
                                "requested_id", id
                        )
                ));
    }
}
