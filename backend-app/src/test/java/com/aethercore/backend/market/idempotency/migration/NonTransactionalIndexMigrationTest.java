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
public class NonTransactionalIndexMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aethercore_nontransactional_test")
            .withUsername("test")
            .withPassword("test");

    private static final Map<String, Object> observations = new LinkedHashMap<>();
    private static final Map<String, Object> runtimeSettings = new LinkedHashMap<>();
    private static final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static final String LOCK_TIMEOUT_SQL = "SET lock_timeout = '5s'";
    private static final String STATEMENT_TIMEOUT_SQL = "SET statement_timeout = '20s'";

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

            String user = postgres.getUsername();
            try (ResultSet rs = stmt.executeQuery("SELECT current_user")) {
                if (rs.next()) user = rs.getString(1);
            }

            runtimeSettings.put("serverVersion", sVer);
            runtimeSettings.put("databaseName", dbName);
            runtimeSettings.put("currentUser", user);
            runtimeSettings.put("dockerImage", "postgres:16-alpine");
            runtimeSettings.put("configuredLockTimeout", "5s");
            runtimeSettings.put("configuredStatementTimeout", "20s");
        }
    }

    private Connection createConnection(String schema) throws SQLException {
        Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            s.execute("SET search_path TO " + schema);
            s.execute(LOCK_TIMEOUT_SQL);
            s.execute(STATEMENT_TIMEOUT_SQL);
        }
        return conn;
    }

    private Flyway createFlyway(String schema, String target) {
        var builder = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:lab-migrations/nontransactional-index")
                .configuration(Map.of(
                        "flyway.postgresql.transactional.lock", "false"
                ))
                .initSql(LOCK_TIMEOUT_SQL + "; " + STATEMENT_TIMEOUT_SQL + ";");
        if (target != null) {
            builder.target(target);
        }
        return builder.load();
    }

    private Map<String, Object> queryIndexCatalog(Connection conn, String schema, String indexName) throws SQLException {
        String sql = "SELECT c.oid AS index_oid, c.relname AS index_name, t.relname AS table_name, " +
                     "n.nspname AS schema_name, i.indisunique, i.indisvalid, i.indisready, i.indislive, " +
                     "pg_get_indexdef(c.oid) AS index_def " +
                     "FROM pg_index i " +
                     "JOIN pg_class c ON c.oid = i.indexrelid " +
                     "JOIN pg_class t ON t.oid = i.indrelid " +
                     "JOIN pg_namespace n ON n.oid = c.relnamespace " +
                     "WHERE n.nspname = ? AND c.relname = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("exists", true);
                    map.put("indexOid", rs.getLong("index_oid"));
                    map.put("indexName", rs.getString("index_name"));
                    map.put("tableName", rs.getString("table_name"));
                    map.put("schemaName", rs.getString("schema_name"));
                    map.put("indisunique", rs.getBoolean("indisunique"));
                    map.put("indisvalid", rs.getBoolean("indisvalid"));
                    map.put("indisready", rs.getBoolean("indisready"));
                    map.put("indislive", rs.getBoolean("indislive"));
                    map.put("indexDef", rs.getString("index_def"));
                    return map;
                }
            }
        }
        Map<String, Object> notFound = new LinkedHashMap<>();
        notFound.put("exists", false);
        return notFound;
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
                row.put("checksum", rs.getObject("checksum") != null ? ((Number) rs.getObject("checksum")).intValue() : null);
                row.put("success", rs.getBoolean("success"));
                list.add(row);
            }
        }
        return list;
    }

    private List<Map<String, Object>> queryProbeRows(Connection conn, String schema) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        String sql = "SELECT id, token FROM " + schema + ".index_probe ORDER BY id";
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", rs.getLong("id"));
                r.put("token", rs.getString("token"));
                rows.add(r);
            }
        }
        return rows;
    }

    private List<Map<String, Object>> queryDuplicateGroups(Connection conn, String schema) throws SQLException {
        List<Map<String, Object>> groups = new ArrayList<>();
        String sql = "SELECT token, count(*) AS cnt FROM " + schema + ".index_probe GROUP BY token HAVING count(*) > 1 ORDER BY token";
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("token", rs.getString("token"));
                g.put("count", rs.getLong("cnt"));
                groups.add(g);
            }
        }
        return groups;
    }

    private String computeResourceHash(String resourcePath) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            assertNotNull(is, "Resource must exist on classpath: " + resourcePath);
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
    @DisplayName("NC1: CREATE INDEX CONCURRENTLY fails inside an explicit transaction block with SQLSTATE 25001")
    void nc1_concurrentIndexInExplicitTransactionFails() throws Exception {
        String schema = "nc1_schema";
        try (Connection conn = createConnection(schema)) {
            // 1. Migrate V1
            Flyway flywayV1 = createFlyway(schema, "1");
            MigrateResult resV1 = flywayV1.migrate();
            assertTrue(resV1.success);
            assertEquals(1, resV1.migrationsExecuted);

            List<Map<String, Object>> initialRows = queryProbeRows(conn, schema);
            assertEquals(3, initialRows.size());

            // 2. Open connection with setAutoCommit(false)
            conn.setAutoCommit(false);

            String cicSql = "CREATE UNIQUE INDEX CONCURRENTLY uq_index_probe_token ON " + schema + ".index_probe (token)";
            String caughtSqlState = null;
            String caughtMessage = null;

            try (Statement s = conn.createStatement()) {
                s.execute(cicSql);
                fail("CREATE INDEX CONCURRENTLY must not succeed inside an explicit transaction block");
            } catch (SQLException e) {
                caughtSqlState = e.getSQLState();
                caughtMessage = e.getMessage();
            }

            assertEquals("25001", caughtSqlState, "PostgreSQL must reject CIC in explicit transaction with SQLSTATE 25001");
            assertNotNull(caughtMessage);
            assertTrue(caughtMessage.toLowerCase().contains("cannot run inside a transaction block") ||
                       caughtMessage.toLowerCase().contains("transaction block"),
                    "Error message must specify cannot run inside a transaction block: " + caughtMessage);

            // 3. Rollback
            conn.rollback();
            conn.setAutoCommit(true);

            // 4. In a fresh autocommit connection, verify no index and rows intact
            try (Connection connFresh = createConnection(schema)) {
                Map<String, Object> cat = queryIndexCatalog(connFresh, schema, "uq_index_probe_token");
                assertFalse((Boolean) cat.get("exists"), "Index must not exist after rollback");

                List<Map<String, Object>> postRollbackRows = queryProbeRows(connFresh, schema);
                assertEquals(3, postRollbackRows.size());
                assertEquals(initialRows, postRollbackRows, "Rows must remain exactly intact after rollback");

                Map<String, Object> obs = new LinkedHashMap<>();
                obs.put("caseId", "NC1");
                obs.put("description", "CREATE INDEX CONCURRENTLY rejected inside explicit transaction block");
                obs.put("schema", schema);
                obs.put("expected", Map.of(
                        "sqlState", "25001",
                        "indexExistsAfterRollback", false,
                        "rowsPreserved", true
                ));
                obs.put("observed", Map.of(
                        "sqlState", caughtSqlState,
                        "errorMessage", caughtMessage,
                        "autoCommitMode", false,
                        "indexCatalogAfterRollback", cat,
                        "indexExistsAfterRollback", false,
                        "rowsAfterRollback", postRollbackRows,
                        "rowsPreserved", true
                ));
                obs.put("assertionsPassed", true);
                observations.put("NC1", obs);
            }
        }
    }

    @Test
    @DisplayName("NC2: Non-transactional index migration build fails due to duplicate data, leaving INVALID index")
    void nc2_nonTransactionalIndexBuildFailsLeavingInvalidIndex() throws Exception {
        String schema = "nc2_schema";
        try (Connection conn = createConnection(schema)) {
            // 1. Migrate V1
            Flyway flywayV1 = createFlyway(schema, "1");
            MigrateResult resV1 = flywayV1.migrate();
            assertTrue(resV1.success);
            assertEquals(1, resV1.migrationsExecuted);

            List<Map<String, Object>> rowsBefore = queryProbeRows(conn, schema);
            assertEquals(3, rowsBefore.size());
            List<Map<String, Object>> histV1 = getFlywayHistory(conn, schema);
            assertEquals(1, histV1.size());
            assertTrue((Boolean) histV1.get(0).get("success"));

            // 2. Migrate V2 (non-transactional with duplicate token 'IRON')
            Flyway flywayV2 = createFlyway(schema, "2");
            String caughtSqlState = null;
            FlywayException caughtFlywayException = null;

            try {
                flywayV2.migrate();
                fail("V2 migration must fail due to duplicate token 'IRON'");
            } catch (FlywayException fe) {
                caughtFlywayException = fe;
                Throwable curr = fe;
                while (curr != null) {
                    if (curr instanceof SQLException se) {
                        caughtSqlState = se.getSQLState();
                        break;
                    }
                    curr = curr.getCause();
                }
            }

            assertNotNull(caughtFlywayException, "FlywayException must be thrown");
            assertEquals("23505", caughtSqlState, "Root cause must be SQLSTATE 23505 (unique_violation)");

            // 3. Inspect catalog via fresh connection
            try (Connection connFresh = createConnection(schema)) {
                Map<String, Object> cat = queryIndexCatalog(connFresh, schema, "uq_index_probe_token");
                assertTrue((Boolean) cat.get("exists"), "Index must exist in pg_class/pg_index");
                assertTrue((Boolean) cat.get("indisunique"), "indisunique must be true");
                assertFalse((Boolean) cat.get("indisvalid"), "indisvalid must be false (INVALID index)!");

                // Inspect Flyway schema history
                List<Map<String, Object>> histAfter = getFlywayHistory(connFresh, schema);
                assertEquals(2, histAfter.size(), "History must contain V1 and failed V2");
                Map<String, Object> v2Hist = histAfter.get(1);
                assertEquals("2", v2Hist.get("version"));
                assertFalse((Boolean) v2Hist.get("success"), "V2 in flyway_schema_history must have success=false");

                // Rows are unchanged
                List<Map<String, Object>> rowsAfter = queryProbeRows(connFresh, schema);
                assertEquals(rowsBefore, rowsAfter, "Rows must remain completely unchanged");
                List<Map<String, Object>> dups = queryDuplicateGroups(connFresh, schema);
                assertEquals(1, dups.size());
                assertEquals("IRON", dups.get(0).get("token"));

                // validate() fails due to failed migration in history
                assertThrows(FlywayException.class, flywayV2::validate, "flyway.validate() must fail when a failed migration exists");

                Map<String, Object> obs = new LinkedHashMap<>();
                obs.put("caseId", "NC2");
                obs.put("description", "Non-transactional index build failure leaves INVALID index in pg_index and failed row in Flyway history");
                obs.put("schema", schema);
                obs.put("expected", Map.of(
                        "migrationFailureSqlState", "23505",
                        "indexExists", true,
                        "indisunique", true,
                        "indisvalid", false,
                        "v2HistorySuccess", false,
                        "rowsPreserved", true
                ));
                Map<String, Object> nc2Obs = new LinkedHashMap<>();
                nc2Obs.put("migrationFailureSqlState", caughtSqlState);
                nc2Obs.put("indexCatalog", cat);
                nc2Obs.put("indexExists", cat.get("exists"));
                nc2Obs.put("indisunique", cat.get("indisunique"));
                nc2Obs.put("indisvalid", cat.get("indisvalid"));
                nc2Obs.put("indisready", cat.get("indisready"));
                nc2Obs.put("indislive", cat.get("indislive"));
                nc2Obs.put("indexOid", cat.get("indexOid"));
                nc2Obs.put("indexDef", cat.get("indexDef"));
                nc2Obs.put("historyRows", histAfter);
                nc2Obs.put("v2HistorySuccess", v2Hist.get("success"));
                nc2Obs.put("rowsAfter", rowsAfter);
                nc2Obs.put("rowsPreserved", true);
                nc2Obs.put("duplicateGroups", dups);
                nc2Obs.put("validationFailed", true);
                obs.put("observed", nc2Obs);
                obs.put("assertionsPassed", true);
                observations.put("NC2", obs);
            }
        }
    }

    @Test
    @DisplayName("NC3: flyway.repair() clears failed history but leaves INVALID index; recovery requires explicit cleanup and retry")
    void nc3_repairDoesNotFixIndexAndRecoveryRequiresCleanup() throws Exception {
        String schema = "nc3_schema";
        String v1Hash = computeResourceHash("/lab-migrations/nontransactional-index/V1__create_index_probe.sql");
        String v2Hash = computeResourceHash("/lab-migrations/nontransactional-index/V2__create_unique_token_index.sql");
        String confHash = computeResourceHash("/lab-migrations/nontransactional-index/V2__create_unique_token_index.sql.conf");

        Map<String, String> fileHashesInitial = Map.of(
                "v1SqlHash", v1Hash,
                "v2SqlHash", v2Hash,
                "v2ConfHash", confHash
        );

        try (Connection conn = createConnection(schema)) {
            // Setup failed V2 migration
            Flyway flywayV1 = createFlyway(schema, "1");
            flywayV1.migrate();

            Flyway flywayV2 = createFlyway(schema, "2");
            try {
                flywayV2.migrate();
                fail("V2 migration must fail due to duplicate token");
            } catch (FlywayException ignored) {
            }

            // --- Checkpoint 1: Before Repair ---
            Map<String, Object> catBeforeRepair = queryIndexCatalog(conn, schema, "uq_index_probe_token");
            assertTrue((Boolean) catBeforeRepair.get("exists"));
            assertFalse((Boolean) catBeforeRepair.get("indisvalid"));
            Long initialOid = (Long) catBeforeRepair.get("indexOid");

            List<Map<String, Object>> histBeforeRepair = getFlywayHistory(conn, schema);
            assertEquals(2, histBeforeRepair.size());
            assertFalse((Boolean) histBeforeRepair.get(1).get("success"));

            List<Map<String, Object>> rowsBeforeRepair = queryProbeRows(conn, schema);
            assertEquals(3, rowsBeforeRepair.size());
            List<Map<String, Object>> dupsBeforeRepair = queryDuplicateGroups(conn, schema);
            assertEquals(1, dupsBeforeRepair.size());

            // --- Checkpoint 2: Step 2: flyway.repair() ---
            flywayV2.repair();

            Map<String, Object> catAfterRepair = queryIndexCatalog(conn, schema, "uq_index_probe_token");
            assertTrue((Boolean) catAfterRepair.get("exists"), "Index must still exist after repair");
            assertFalse((Boolean) catAfterRepair.get("indisvalid"), "Index must still be INVALID after repair");
            assertEquals(initialOid, catAfterRepair.get("indexOid"), "Index OID must remain identical");

            List<Map<String, Object>> histAfterRepair = getFlywayHistory(conn, schema);
            assertEquals(1, histAfterRepair.size(), "Failed V2 must be removed from history by repair()");
            assertEquals("1", histAfterRepair.get(0).get("version"));
            assertTrue((Boolean) histAfterRepair.get(0).get("success"));

            List<Map<String, Object>> rowsAfterRepair = queryProbeRows(conn, schema);
            assertEquals(rowsBeforeRepair, rowsAfterRepair, "Rows must remain unchanged after repair");
            List<Map<String, Object>> dupsAfterRepair = queryDuplicateGroups(conn, schema);
            assertEquals(1, dupsAfterRepair.size(), "Duplicates must still exist after repair");

            // --- Checkpoint 3: Step 3: Object and Data Cleanup ---
            try (Statement s = conn.createStatement()) {
                s.execute("DROP INDEX " + schema + ".uq_index_probe_token");
            }
            Map<String, Object> catAfterDrop = queryIndexCatalog(conn, schema, "uq_index_probe_token");
            assertFalse((Boolean) catAfterDrop.get("exists"), "Index must be dropped");

            int updatedRows;
            try (PreparedStatement ps = conn.prepareStatement("UPDATE " + schema + ".index_probe SET token = 'SILVER' WHERE id = 2")) {
                updatedRows = ps.executeUpdate();
            }
            assertEquals(1, updatedRows, "Exactly 1 duplicate row updated to SILVER");

            List<Map<String, Object>> rowsAfterCleanup = queryProbeRows(conn, schema);
            assertEquals(3, rowsAfterCleanup.size());
            List<Map<String, Object>> dupsAfterCleanup = queryDuplicateGroups(conn, schema);
            assertTrue(dupsAfterCleanup.isEmpty(), "No duplicate tokens must remain after cleanup");

            String v1HashAfter = computeResourceHash("/lab-migrations/nontransactional-index/V1__create_index_probe.sql");
            String v2HashAfter = computeResourceHash("/lab-migrations/nontransactional-index/V2__create_unique_token_index.sql");
            String confHashAfter = computeResourceHash("/lab-migrations/nontransactional-index/V2__create_unique_token_index.sql.conf");
            assertEquals(v1Hash, v1HashAfter, "V1 script hash must be unchanged");
            assertEquals(v2Hash, v2HashAfter, "V2 script hash must be unchanged");
            assertEquals(confHash, confHashAfter, "V2 conf hash must be unchanged");

            // --- Checkpoint 4: Step 4: Retry Migration and Enforce Uniqueness ---
            MigrateResult retryResult = flywayV2.migrate();
            assertTrue(retryResult.success, "Retry migration must succeed");
            assertEquals(1, retryResult.migrationsExecuted);

            Map<String, Object> catFinal = queryIndexCatalog(conn, schema, "uq_index_probe_token");
            assertTrue((Boolean) catFinal.get("exists"));
            assertTrue((Boolean) catFinal.get("indisunique"));
            assertTrue((Boolean) catFinal.get("indisvalid"), "Final index must be VALID!");
            assertTrue((Boolean) catFinal.get("indisready"));

            List<Map<String, Object>> histFinal = getFlywayHistory(conn, schema);
            assertEquals(2, histFinal.size(), "Final history must contain V1 and V2");
            assertTrue((Boolean) histFinal.get(0).get("success"));
            assertTrue((Boolean) histFinal.get(1).get("success"));

            // Test unique constraint enforcement: duplicate insert fails with 23505
            String duplicateInsertSqlState = null;
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + schema + ".index_probe (id, token) VALUES (4, 'IRON')")) {
                ps.executeUpdate();
                fail("Inserting duplicate token 'IRON' must fail due to unique index");
            } catch (SQLException e) {
                duplicateInsertSqlState = e.getSQLState();
            }
            assertEquals("23505", duplicateInsertSqlState, "Duplicate insert must fail with SQLSTATE 23505");

            // Verify rows intact after rejected insert
            List<Map<String, Object>> rowsAfterRejectedInsert = queryProbeRows(conn, schema);
            assertEquals(3, rowsAfterRejectedInsert.size());

            // Valid insert succeeds
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + schema + ".index_probe (id, token) VALUES (4, 'DIAMOND')")) {
                int ins = ps.executeUpdate();
                assertEquals(1, ins);
            }
            List<Map<String, Object>> rowsFinal = queryProbeRows(conn, schema);
            assertEquals(4, rowsFinal.size());

            // Repeating migrate runs 0 migrations
            MigrateResult repeatResult = flywayV2.migrate();
            assertEquals(0, repeatResult.migrationsExecuted);

            // Validation succeeds
            assertDoesNotThrow(flywayV2::validate, "flyway.validate() must pass cleanly");

            Map<String, Object> obs = new LinkedHashMap<>();
            obs.put("caseId", "NC3");
            obs.put("description", "Flyway repair cleans history but leaves INVALID index; recovery requires DROP INDEX, data fix, and retry");
            obs.put("schema", schema);
            obs.put("expected", Map.of(
                    "repairRemovesFailedHistory", true,
                    "repairLeavesIndexInvalid", true,
                    "cleanupSuccess", true,
                    "retrySuccess", true,
                    "finalIndexValid", true,
                    "uniquenessEnforced", true
            ));
            obs.put("observed", Map.of(
                    "fileHashesUnchanged", true,
                    "fileHashes", fileHashesInitial,
                    "checkpoint1_beforeRepair", Map.of(
                            "history", histBeforeRepair,
                            "indexCatalog", catBeforeRepair,
                            "rows", rowsBeforeRepair,
                            "duplicateGroups", dupsBeforeRepair
                    ),
                    "checkpoint2_afterRepair", Map.of(
                            "history", histAfterRepair,
                            "indexCatalog", catAfterRepair,
                            "rows", rowsAfterRepair,
                            "duplicateGroups", dupsAfterRepair
                    ),
                    "checkpoint3_afterCleanup", Map.of(
                            "indexCatalog", catAfterDrop,
                            "rows", rowsAfterCleanup,
                            "duplicateGroups", dupsAfterCleanup
                    ),
                    "checkpoint4_afterRetry", Map.of(
                            "retrySuccess", retryResult.success,
                            "indexCatalog", catFinal,
                            "history", histFinal,
                            "duplicateInsertSqlState", duplicateInsertSqlState,
                            "rowsFinal", rowsFinal,
                            "repeatMigrationCount", repeatResult.migrationsExecuted,
                            "validationPassed", true
                    )
            ));
            obs.put("assertionsPassed", true);
            observations.put("NC3", obs);
        }
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

        File file = new File(dir, "nontransactional-index-observations.json");
        String runId = System.getProperty("lab.runId", "ad-hoc-" + Instant.now().toEpochMilli());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1.0.0");
        payload.put("runId", runId);
        payload.put("timestamp", Instant.now().toString());

        Map<String, Object> dbInfo = new LinkedHashMap<>(runtimeSettings);
        payload.put("database", dbInfo);

        Map<String, Object> flywayInfo = new LinkedHashMap<>();
        flywayInfo.put("version", "10.10.0");
        flywayInfo.put("scriptTransactionMode", "executeInTransaction=false via sidecar .conf");
        flywayInfo.put("lockMode", "flyway.postgresql.transactional.lock=false (session-level advisory lock)");
        payload.put("flyway", flywayInfo);

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
            throw new RuntimeException("Failed to export non-transactional index observations to " + file.getAbsolutePath(), e);
        }
    }
}
