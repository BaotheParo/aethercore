package com.aethercore.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test against real PostgreSQL container via Testcontainers.
 * Validates the full request pipeline:
 * MockMvc HTTP Request -> DispatcherServlet -> LabEntryController -> LabEntryRepository -> Real PostgreSQL Container
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
public class LabEntryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aethercore_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.sql.init.mode", () -> "always");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/lab/entries/{id} returns 200 OK and data when ID exists in PostgreSQL")
    void shouldReturnLabEntryWhenExists() throws Exception {
        mockMvc.perform(get("/api/lab/entries/entry-01")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value("entry-01"))
                .andExpect(jsonPath("$.label").value("AetherCore Baseline Initial Lab Entry"));
    }

    @Test
    @DisplayName("GET /api/lab/entries/{id} returns 404 NOT FOUND when ID does not exist")
    void shouldReturn404WhenNotFound() throws Exception {
        mockMvc.perform(get("/api/lab/entries/non-existent-id")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.requested_id").value("non-existent-id"));
    }
}
