package com.aethercore.backend.market.idempotency.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
public class MarketIdempotencyMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aethercore_migration_test")
            .withUsername("test")
            .withPassword("test");

    private static final Map<String, Object> observations = new LinkedHashMap<>();
    private static final Map<String, Object> runtimeSettings = new LinkedHashMap<>();
    private static final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @BeforeAll
    static void captureRuntime() throws Exception {
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            String sVer = "unknown";
            try (ResultSet rs = stmt.executeQuery("SHOW server_version")) {
                if (rs.next()) sVer = rs.getString(1);
            }

            String dbName = postgres.getDatabaseName();
            try (ResultSet rs = stmt.executeQuery("SELECT current_database()")) {
                if (rs.next()) dbName = rs.getString(1);
            }

            String iso = "unknown";
            try (ResultSet rs = stmt.executeQuery("SHOW transaction_isolation")) {
                if (rs.next()) iso = rs.getString(1);
            }

            runtimeSettings.put("image", postgres.getDockerImageName());
            runtimeSettings.put("serverVersion", sVer);
            runtimeSettings.put("databaseName", dbName);
            runtimeSettings.put("isolationDefault", iso);
        }
    }

    private Connection createConnection(String schema) throws SQLException {
        Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            stmt.execute("SET search_path TO " + schema + ", public");
        }
        return conn;
    }

    private Flyway createFlyway(String schema, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private String getConstraintDef(Connection conn, String schema, String constraintName) throws SQLException {
        String sql = "SELECT pg_get_constraintdef(c.oid) " +
                     "FROM pg_constraint c " +
                     "JOIN pg_namespace n ON n.oid = c.connamespace " +
                     "WHERE n.nspname = ? AND c.conname = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        }
        return null;
    }

    private boolean isConstraintValidated(Connection conn, String schema, String constraintName) throws SQLException {
        String sql = "SELECT c.convalidated " +
                     "FROM pg_constraint c " +
                     "JOIN pg_namespace n ON n.oid = c.connamespace " +
                     "WHERE n.nspname = ? AND c.conname = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean(1);
                }
            }
        }
        return false;
    }

    private List<Map<String, Object>> getFlywayHistory(Connection conn, String schema) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT installed_rank, version, description, type, script, checksum, success " +
                     "FROM " + schema + ".flyway_schema_history ORDER BY installed_rank";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("installed_rank", rs.getInt("installed_rank"));
                row.put("version", rs.getString("version"));
                row.put("description", rs.getString("description"));
                row.put("type", rs.getString("type"));
                row.put("script", rs.getString("script"));
                row.put("checksum", rs.getInt("checksum"));
                row.put("success", rs.getBoolean("success"));
                list.add(row);
            }
        }
        return list;
    }

    private String computeV6ResourceHash() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = getClass().getResourceAsStream("/db/migration/V6__fix_market_idempotency_state_shape.sql")) {
            assertNotNull(is, "V6 migration resource must exist on classpath");
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    @DisplayName("MG1: Recreate V5 flaw and upgrade to V6 with valid data preserved")
    void mg1_recreateV5FlawAndUpgradeToV6() throws Exception {
        String schema = "mg1_schema";
        try (Connection conn = createConnection(schema)) {
            try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT current_schema()")) {
                assertTrue(rs.next());
                assertEquals(schema, rs.getString(1));
            }

            // 1. Migrate to V5
            Flyway flywayV5 = createFlyway(schema, "5");
            MigrateResult resV5 = flywayV5.migrate();
            assertTrue(resV5.success);

            List<Map<String, Object>> v5History = getFlywayHistory(conn, schema);
            assertEquals(5, v5History.size(), "V1..V5 must be applied");

            String v5ConstraintDef = getConstraintDef(conn, schema, "chk_idempotency_state_shape");
            assertNotNull(v5ConstraintDef);

            // 2. Insert malformed COMPLETED/null/null into V5
            UUID callerMalformed = UUID.randomUUID();
            UUID keyMalformed = UUID.randomUUID();
            String insertMalformedSql = "INSERT INTO " + schema + ".market_idempotency " +
                    "(caller_id, operation, idempotency_key, request_sku, request_quantity, request_fingerprint, state, result_code, purchase_id) " +
                    "VALUES (?, 'PURCHASE_V1', ?, 'SKU_MALFORMED', 1, 'fp_malformed', 'COMPLETED', NULL, NULL)";

            boolean v5AcceptedMalformed = false;
            try (PreparedStatement ps = conn.prepareStatement(insertMalformedSql)) {
                ps.setObject(1, callerMalformed);
                ps.setObject(2, keyMalformed);
                int rows = ps.executeUpdate();
                if (rows == 1) {
                    v5AcceptedMalformed = true;
                }
            }
            assertTrue(v5AcceptedMalformed, "V5 must accept COMPLETED/null/null due to UNKNOWN truth-value loophole");

            // 3. Delete malformed fixture from lab schema
            String deleteMalformedSql = "DELETE FROM " + schema + ".market_idempotency WHERE caller_id = ? AND idempotency_key = ?";
            int deleted;
            try (PreparedStatement ps = conn.prepareStatement(deleteMalformedSql)) {
                ps.setObject(1, callerMalformed);
                ps.setObject(2, keyMalformed);
                deleted = ps.executeUpdate();
            }
            assertEquals(1, deleted, "Exactly 1 malformed row deleted");

            // 4. Seed valid rows for preservation test
            try (Statement s = conn.createStatement()) {
                s.execute("INSERT INTO " + schema + ".market_stock (sku, available, version) VALUES ('POTION_1', 10, 0)");
            }

            UUID purchaseId = UUID.randomUUID();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + schema + ".market_purchase (id, sku, quantity) VALUES (?, 'POTION_1', 2)")) {
                ps.setObject(1, purchaseId);
                ps.executeUpdate();
            }

            UUID callerA = UUID.randomUUID();
            UUID keyA = UUID.randomUUID();
            UUID callerB = UUID.randomUUID();
            UUID keyB = UUID.randomUUID();
            UUID callerC = UUID.randomUUID();
            UUID keyC = UUID.randomUUID();

            String insertIdem = "INSERT INTO " + schema + ".market_idempotency " +
                    "(caller_id, operation, idempotency_key, request_sku, request_quantity, request_fingerprint, state, result_code, purchase_id) " +
                    "VALUES (?, 'PURCHASE_V1', ?, ?, ?, ?, ?, ?, ?)";

            // Row A: PROCESSING/null/null
            try (PreparedStatement ps = conn.prepareStatement(insertIdem)) {
                ps.setObject(1, callerA);
                ps.setObject(2, keyA);
                ps.setString(3, "POTION_1");
                ps.setInt(4, 1);
                ps.setString(5, "fp_a");
                ps.setString(6, "PROCESSING");
                ps.setNull(7, Types.VARCHAR);
                ps.setNull(8, Types.OTHER);
                ps.executeUpdate();
            }

            // Row B: COMPLETED/NOT_AVAILABLE/null
            try (PreparedStatement ps = conn.prepareStatement(insertIdem)) {
                ps.setObject(1, callerB);
                ps.setObject(2, keyB);
                ps.setString(3, "POTION_1");
                ps.setInt(4, 20);
                ps.setString(5, "fp_b");
                ps.setString(6, "COMPLETED");
                ps.setString(7, "NOT_AVAILABLE");
                ps.setNull(8, Types.OTHER);
                ps.executeUpdate();
            }

            // Row C: COMPLETED/SUCCESS/purchaseId
            try (PreparedStatement ps = conn.prepareStatement(insertIdem)) {
                ps.setObject(1, callerC);
                ps.setObject(2, keyC);
                ps.setString(3, "POTION_1");
                ps.setInt(4, 2);
                ps.setString(5, "fp_c");
                ps.setString(6, "COMPLETED");
                ps.setString(7, "SUCCESS");
                ps.setObject(8, purchaseId);
                ps.executeUpdate();
            }

            // 5. Snapshot pre-upgrade rows
            Map<String, String> preRows = queryIdempotencySummary(conn, schema);
            assertEquals(3, preRows.size());

            // 6. Migrate to V6
            Flyway flywayV6 = createFlyway(schema, "6");
            MigrateResult resV6 = flywayV6.migrate();
            assertTrue(resV6.success);
            assertEquals(1, resV6.migrationsExecuted);

            String v6ConstraintDef = getConstraintDef(conn, schema, "chk_idempotency_state_shape");
            assertNotNull(v6ConstraintDef);
            assertTrue(v6ConstraintDef.contains("result_code IS NOT NULL"), "V6 constraint must enforce result_code IS NOT NULL for COMPLETED");
            assertTrue(isConstraintValidated(conn, schema, "chk_idempotency_state_shape"), "Constraint must be validated");

            // 7. Snapshot post-upgrade rows and history
            Map<String, String> postRows = queryIdempotencySummary(conn, schema);
            assertEquals(preRows, postRows, "Pre-upgrade and post-upgrade data must match identically");

            List<Map<String, Object>> v6History = getFlywayHistory(conn, schema);
            assertEquals(6, v6History.size());
            for (int i = 0; i < 5; i++) {
                assertEquals(v5History.get(i).get("checksum"), v6History.get(i).get("checksum"), "V1..V5 checksums must remain identical");
            }

            // 8. Repeat migrate: idempotency
            MigrateResult resV6Repeat = flywayV6.migrate();
            assertEquals(0, resV6Repeat.migrationsExecuted);
            flywayV6.validate();

            List<Map<String, Object>> finalHistory = getFlywayHistory(conn, schema);
            assertEquals(6, finalHistory.size(), "History row count must remain 6");

            // Record Observation
            Map<String, Object> obs = new LinkedHashMap<>();
            obs.put("caseId", "MG1");
            obs.put("description", "Recreate V5 flaw and upgrade to V6 with valid data preserved");
            obs.put("schema", schema);
            obs.put("expected", Map.of("v5MalformedAccepted", true, "v6Success", true, "validDataPreserved", true, "repeatMigrationCount", 0));
            obs.put("observed", Map.of(
                    "v5MalformedAccepted", v5AcceptedMalformed,
                    "v5ConstraintDef", v5ConstraintDef,
                    "v6Success", resV6.success,
                    "v6ConstraintDef", v6ConstraintDef,
                    "v6Validated", true,
                    "validDataPreserved", true,
                    "historyCount", finalHistory.size(),
                    "repeatMigrationCount", resV6Repeat.migrationsExecuted
            ));
            obs.put("assertionsPassed", true);
            observations.put("MG1", obs);
        }
    }

    @Test
    @DisplayName("MG2: Truth table shape validation after V6 and UPDATE rejection")
    void mg2_truthTableShapeAfterV6() throws Exception {
        String schema = "mg2_schema";
        try (Connection conn = createConnection(schema)) {
            Flyway flyway = createFlyway(schema, "6");
            flyway.migrate();

            try (Statement s = conn.createStatement()) {
                s.execute("INSERT INTO " + schema + ".market_stock (sku, available, version) VALUES ('POTION_2', 10, 0)");
            }

            UUID purchaseId = UUID.randomUUID();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + schema + ".market_purchase (id, sku, quantity) VALUES (?, 'POTION_2', 1)")) {
                ps.setObject(1, purchaseId);
                ps.executeUpdate();
            }

            record TruthVariant(String state, String resultCode, boolean withPurchase, String expected) {}

            List<TruthVariant> variants = List.of(
                    new TruthVariant("PROCESSING", null, false, "ACCEPT"),
                    new TruthVariant("COMPLETED", "NOT_AVAILABLE", false, "ACCEPT"),
                    new TruthVariant("COMPLETED", "SUCCESS", true, "ACCEPT"),
                    new TruthVariant("COMPLETED", null, false, "REJECT"),
                    new TruthVariant("COMPLETED", null, true, "REJECT"),
                    new TruthVariant("COMPLETED", "SUCCESS", false, "REJECT"),
                    new TruthVariant("COMPLETED", "NOT_AVAILABLE", true, "REJECT"),
                    new TruthVariant("PROCESSING", "SUCCESS", true, "REJECT")
            );

            List<Map<String, Object>> truthTableResults = new ArrayList<>();

            for (TruthVariant tv : variants) {
                UUID caller = UUID.randomUUID();
                UUID key = UUID.randomUUID();
                UUID pId = tv.withPurchase ? purchaseId : null;

                String insertSql = "INSERT INTO " + schema + ".market_idempotency " +
                        "(caller_id, operation, idempotency_key, request_sku, request_quantity, request_fingerprint, state, result_code, purchase_id) " +
                        "VALUES (?, 'PURCHASE_V1', ?, 'POTION_2', 1, 'fp_test', ?, ?, ?)";

                String actualOutcome;
                String sqlState = null;

                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setObject(1, caller);
                    ps.setObject(2, key);
                    ps.setString(3, tv.state);
                    if (tv.resultCode != null) {
                        ps.setString(4, tv.resultCode);
                    } else {
                        ps.setNull(4, Types.VARCHAR);
                    }
                    if (pId != null) {
                        ps.setObject(5, pId);
                    } else {
                        ps.setNull(5, Types.OTHER);
                    }
                    ps.executeUpdate();
                    actualOutcome = "ACCEPT";
                } catch (SQLException e) {
                    actualOutcome = "REJECT";
                    sqlState = e.getSQLState();
                    assertTrue(e.getMessage().contains("chk_idempotency_state_shape"), "Failure message must mention constraint chk_idempotency_state_shape");
                }

                assertEquals(tv.expected, actualOutcome, "Variant " + tv + " failed expectation");
                if ("REJECT".equals(tv.expected)) {
                    assertEquals("23514", sqlState, "Rejected variant must produce SQLSTATE 23514 (check_violation)");
                }

                Map<String, Object> r = new LinkedHashMap<>();
                r.put("state", tv.state);
                r.put("resultCode", tv.resultCode);
                r.put("withPurchase", tv.withPurchase);
                r.put("expected", tv.expected);
                r.put("actual", actualOutcome);
                r.put("sqlState", sqlState);
                truthTableResults.add(r);
            }

            // Test UPDATE rejection: from valid COMPLETED/NOT_AVAILABLE/null, attempt UPDATE result_code = NULL
            UUID updateCaller = UUID.randomUUID();
            UUID updateKey = UUID.randomUUID();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + schema + ".market_idempotency (caller_id, operation, idempotency_key, request_sku, request_quantity, request_fingerprint, state, result_code, purchase_id) " +
                    "VALUES (?, 'PURCHASE_V1', ?, 'POTION_2', 1, 'fp_up', 'COMPLETED', 'NOT_AVAILABLE', NULL)")) {
                ps.setObject(1, updateCaller);
                ps.setObject(2, updateKey);
                ps.executeUpdate();
            }

            String updateSqlState = null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + schema + ".market_idempotency SET result_code = NULL WHERE caller_id = ? AND idempotency_key = ?")) {
                ps.setObject(1, updateCaller);
                ps.setObject(2, updateKey);
                ps.executeUpdate();
                fail("UPDATE to COMPLETED with NULL result_code must be rejected by V6");
            } catch (SQLException e) {
                updateSqlState = e.getSQLState();
                assertEquals("23514", updateSqlState);
                assertTrue(e.getMessage().contains("chk_idempotency_state_shape"));
            }

            // Assert row in DB remains intact
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT result_code FROM " + schema + ".market_idempotency WHERE caller_id = ? AND idempotency_key = ?")) {
                ps.setObject(1, updateCaller);
                ps.setObject(2, updateKey);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("NOT_AVAILABLE", rs.getString(1), "Original row must remain unchanged");
                }
            }

            Map<String, Object> obs = new LinkedHashMap<>();
            obs.put("caseId", "MG2");
            obs.put("description", "Truth table shape validation after V6 and UPDATE rejection");
            obs.put("schema", schema);
            obs.put("expected", Map.of("allVariantsMatch", true, "updateRejected", true, "updateSqlState", "23514"));
            obs.put("observed", Map.of(
                    "truthTable", truthTableResults,
                    "updateRejected", true,
                    "updateSqlState", updateSqlState,
                    "rowIntactAfterUpdateFailure", true
            ));
            obs.put("assertionsPassed", true);
            observations.put("MG2", obs);
        }
    }

    @Test
    @DisplayName("MG3: Migration fail due to legacy malformed data, DDL rollback, and clean retry without file changes")
    void mg3_migrationFailDdlRollbackAndCleanRetry() throws Exception {
        String schema = "mg3_schema";
        String v6FileHashBefore = computeV6ResourceHash();

        try (Connection conn = createConnection(schema)) {
            // 1. Migrate to V5
            Flyway flywayV5 = createFlyway(schema, "5");
            flywayV5.migrate();

            List<Map<String, Object>> v5History = getFlywayHistory(conn, schema);
            assertEquals(5, v5History.size());

            String v5ConstraintDef = getConstraintDef(conn, schema, "chk_idempotency_state_shape");
            assertNotNull(v5ConstraintDef);

            // 2. Seed stock & purchase for valid row
            try (Statement s = conn.createStatement()) {
                s.execute("INSERT INTO " + schema + ".market_stock (sku, available, version) VALUES ('POTION_3', 10, 0)");
            }
            UUID validPurchaseId = UUID.randomUUID();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + schema + ".market_purchase (id, sku, quantity) VALUES (?, 'POTION_3', 1)")) {
                ps.setObject(1, validPurchaseId);
                ps.executeUpdate();
            }

            // Seed valid row
            UUID callerValid = UUID.randomUUID();
            UUID keyValid = UUID.randomUUID();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + schema + ".market_idempotency (caller_id, operation, idempotency_key, request_sku, request_quantity, request_fingerprint, state, result_code, purchase_id) " +
                    "VALUES (?, 'PURCHASE_V1', ?, 'POTION_3', 1, 'fp_valid', 'COMPLETED', 'SUCCESS', ?)")) {
                ps.setObject(1, callerValid);
                ps.setObject(2, keyValid);
                ps.setObject(3, validPurchaseId);
                ps.executeUpdate();
            }

            // Seed malformed row: COMPLETED/null/null
            UUID callerMalformed = UUID.randomUUID();
            UUID keyMalformed = UUID.randomUUID();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + schema + ".market_idempotency (caller_id, operation, idempotency_key, request_sku, request_quantity, request_fingerprint, state, result_code, purchase_id) " +
                    "VALUES (?, 'PURCHASE_V1', ?, 'POTION_3', 1, 'fp_mal', 'COMPLETED', NULL, NULL)")) {
                ps.setObject(1, callerMalformed);
                ps.setObject(2, keyMalformed);
                ps.executeUpdate();
            }

            // 3. Attempt V6 migration: must fail due to malformed data
            Flyway flywayV6 = createFlyway(schema, "6");
            String migrationFailureSqlState = null;
            try {
                flywayV6.migrate();
                fail("Migrating to V6 with malformed legacy row must fail");
            } catch (FlywayException fe) {
                Throwable curr = fe;
                while (curr != null) {
                    if (curr instanceof SQLException se) {
                        migrationFailureSqlState = se.getSQLState();
                        break;
                    }
                    curr = curr.getCause();
                }
            }
            assertEquals("23514", migrationFailureSqlState, "Failure must have SQLSTATE 23514");

            // 4. In a fresh connection, verify rollback:
            try (Connection connCheck = createConnection(schema)) {
                String restoredDef = getConstraintDef(connCheck, schema, "chk_idempotency_state_shape");
                assertNotNull(restoredDef, "Old constraint must still exist after rollback");
                assertEquals(v5ConstraintDef, restoredDef, "Constraint definition must match V5 definition");

                // Rows are preserved
                try (Statement s = connCheck.createStatement();
                     ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + schema + ".market_idempotency")) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1), "Both malformed and valid rows must still exist");
                }

                // History table has V1..V5, and NO version 6 row (neither applied nor failed)
                List<Map<String, Object>> histAfterFail = getFlywayHistory(connCheck, schema);
                assertEquals(5, histAfterFail.size(), "History must not contain V6");
                for (Map<String, Object> h : histAfterFail) {
                    assertNotEquals("6", h.get("version"));
                }

                // 5. Delete exactly the malformed row in lab schema
                int deletedRows;
                try (PreparedStatement ps = connCheck.prepareStatement(
                        "DELETE FROM " + schema + ".market_idempotency WHERE caller_id = ? AND idempotency_key = ?")) {
                    ps.setObject(1, callerMalformed);
                    ps.setObject(2, keyMalformed);
                    deletedRows = ps.executeUpdate();
                }
                assertEquals(1, deletedRows);

                // 6. Verify V6 file content hash is completely identical
                String v6FileHashAfter = computeV6ResourceHash();
                assertEquals(v6FileHashBefore, v6FileHashAfter, "V6 file hash must be identical before failure and after retry");

                // 7. Retry migrate to V6
                MigrateResult retryRes = flywayV6.migrate();
                assertTrue(retryRes.success);
                assertEquals(1, retryRes.migrationsExecuted);

                // Verify V6 constraint definition is now in place
                String newDef = getConstraintDef(connCheck, schema, "chk_idempotency_state_shape");
                assertNotNull(newDef);
                assertTrue(newDef.contains("result_code IS NOT NULL"));

                // Verify valid data was preserved
                try (Statement s = connCheck.createStatement();
                     ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + schema + ".market_idempotency")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1), "Valid row preserved");
                }

                // Verify inserting malformed row now fails
                assertThrows(SQLException.class, () -> {
                    try (PreparedStatement ps = connCheck.prepareStatement(
                            "INSERT INTO " + schema + ".market_idempotency (caller_id, operation, idempotency_key, request_sku, request_quantity, request_fingerprint, state, result_code, purchase_id) " +
                            "VALUES (?, 'PURCHASE_V1', ?, 'POTION_3', 1, 'fp_mal2', 'COMPLETED', NULL, NULL)")) {
                        ps.setObject(1, UUID.randomUUID());
                        ps.setObject(2, UUID.randomUUID());
                        ps.executeUpdate();
                    }
                });

                // Repeating migrate runs 0 migrations
                MigrateResult repeatRes = flywayV6.migrate();
                assertEquals(0, repeatRes.migrationsExecuted);

                List<Map<String, Object>> finalHist = getFlywayHistory(connCheck, schema);
                assertEquals(6, finalHist.size());

                Map<String, Object> obs = new LinkedHashMap<>();
                obs.put("caseId", "MG3");
                obs.put("description", "Migration fail due to legacy malformed data, DDL rollback, and clean retry");
                obs.put("schema", schema);
                obs.put("expected", Map.of("migrationFailureSqlState", "23514", "oldConstraintRestored", true, "v6HistoryCountAfterFailure", 0, "retrySuccess", true));
                obs.put("observed", Map.of(
                        "migrationFailureSqlState", migrationFailureSqlState,
                        "oldConstraintRestored", true,
                        "v6HistoryCountAfterFailure", 0,
                        "v6FileHashUnchanged", true,
                        "v6FileHash", v6FileHashAfter,
                        "retrySuccess", retryRes.success,
                        "finalHistoryCount", finalHist.size()
                ));
                obs.put("assertionsPassed", true);
                observations.put("MG3", obs);
            }
        }
    }

    private Map<String, String> queryIdempotencySummary(Connection conn, String schema) throws SQLException {
        Map<String, String> map = new LinkedHashMap<>();
        String sql = "SELECT caller_id, idempotency_key, request_sku, request_quantity, request_fingerprint, state, result_code, purchase_id " +
                     "FROM " + schema + ".market_idempotency ORDER BY caller_id, idempotency_key";
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                String key = rs.getString("caller_id") + ":" + rs.getString("idempotency_key");
                String val = rs.getString("request_sku") + "|" +
                             rs.getInt("request_quantity") + "|" +
                             rs.getString("request_fingerprint") + "|" +
                             rs.getString("state") + "|" +
                             rs.getString("result_code") + "|" +
                             rs.getString("purchase_id");
                map.put(key, val);
            }
        }
        return map;
    }

    @AfterAll
    static void tearDownAll() {
        String evidenceDirProp = System.getProperty("lab.evidenceDir");
        File dir;
        if (evidenceDirProp != null && !evidenceDirProp.isBlank()) {
            dir = new File(evidenceDirProp);
        } else {
            dir = new File("target/lab-evidence/ad-hoc");
            if (!dir.exists() && new File("backend-app/target").exists()) {
                dir = new File("backend-app/target/lab-evidence/ad-hoc");
            }
        }
        dir.mkdirs();

        File file = new File(dir, "migration-observations.json");
        String runId = System.getProperty("lab.runId", "ad-hoc-" + Instant.now().toEpochMilli());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1.0.0");
        payload.put("runId", runId);
        payload.put("timestamp", Instant.now().toString());

        Map<String, Object> dbInfo = new LinkedHashMap<>(runtimeSettings);
        payload.put("database", dbInfo);

        List<String> keys = new ArrayList<>(observations.keySet());
        Collections.sort(keys);
        List<Object> scenariosList = new ArrayList<>();
        for (String k : keys) {
            scenariosList.add(observations.get(k));
        }
        payload.put("scenarios", scenariosList);

        try {
            objectMapper.writeValue(file, payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to export migration observations to " + file.getAbsolutePath(), e);
        }
    }
}
