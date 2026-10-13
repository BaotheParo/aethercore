package com.aethercore.backend.queryplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2-E5A: PostgreSQL Query Plan, Index Selection, and Cost Model Experiments.
 *
 * Scenarios:
 * - EP1: No secondary index (Primary Key only) -> Limit -> Sort -> Seq Scan.
 * - EP2: Single-column index on buyer_id -> Index/Bitmap scan narrows buyer, but still requires separate Sort.
 * - EP3: Composite index on (buyer_id, status, created_at DESC, id DESC) -> Index scan satisfies filter & order, no Sort node.
 * - EP4: Status index with low selectivity query (90% match) -> Seq Scan preferred over Index Scan.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.MethodName.class)
public class PostgresQueryPlanExperimentsTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aethercore_queryplan_test")
            .withUsername("test")
            .withPassword("test");

    private static final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static final String Q1_SQL = """
            SELECT id, buyer_id, status, created_at, payload
            FROM query_plan_trade
            WHERE buyer_id = ?
              AND status = ?
            ORDER BY created_at DESC, id DESC
            LIMIT ?
            """;

    private static final String Q2_SQL = """
            SELECT id, buyer_id, status, created_at, payload
            FROM query_plan_trade
            WHERE status = ?
            """;

    private static final List<Long> EXPECTED_Q1_IDS = List.of(90042L, 80042L, 70042L, 60042L, 50042L);

    private static String postgresServerVersion;
    private static String jdbcDriverVersion;
    private static final Map<String, Object> runtimeSettings = new LinkedHashMap<>();
    private static final Map<String, QueryPlanCaseObservation> observations = new ConcurrentHashMap<>();

    @BeforeAll
    static void setUpAll() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", postgres.getUsername());
        props.setProperty("password", postgres.getPassword());
        props.setProperty("prepareThreshold", "0"); // Disable generic prepared plans

        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), props);
             Statement stmt = conn.createStatement()) {

            // Configure connection-level tuning for reproducible plans
            stmt.execute("SET max_parallel_workers_per_gather = 0");
            stmt.execute("SET jit = off");
            stmt.execute("SET statement_timeout = '30s'");

            DatabaseMetaData meta = conn.getMetaData();
            jdbcDriverVersion = meta.getDriverVersion();

            // Query server settings
            postgresServerVersion = querySetting(stmt, "SHOW server_version");
            runtimeSettings.put("server_version", postgresServerVersion);
            runtimeSettings.put("driver_version", jdbcDriverVersion);
            runtimeSettings.put("image", "postgres:16-alpine");
            runtimeSettings.put("work_mem", querySetting(stmt, "SHOW work_mem"));
            runtimeSettings.put("random_page_cost", querySetting(stmt, "SHOW random_page_cost"));
            runtimeSettings.put("seq_page_cost", querySetting(stmt, "SHOW seq_page_cost"));
            runtimeSettings.put("effective_cache_size", querySetting(stmt, "SHOW effective_cache_size"));
            runtimeSettings.put("enable_seqscan", querySetting(stmt, "SHOW enable_seqscan"));
            runtimeSettings.put("enable_indexscan", querySetting(stmt, "SHOW enable_indexscan"));
            runtimeSettings.put("enable_bitmapscan", querySetting(stmt, "SHOW enable_bitmapscan"));
            runtimeSettings.put("max_parallel_workers_per_gather", querySetting(stmt, "SHOW max_parallel_workers_per_gather"));
            runtimeSettings.put("jit", querySetting(stmt, "SHOW jit"));
            runtimeSettings.put("statement_timeout", querySetting(stmt, "SHOW statement_timeout"));
            runtimeSettings.put("pgjdbc_prepareThreshold", 0);

            // 1. Initialize schema
            try (InputStream is = PostgresQueryPlanExperimentsTest.class.getResourceAsStream("/sql/query-plan-probe.sql")) {
                if (is == null) {
                    throw new IllegalStateException("Resource /sql/query-plan-probe.sql not found on classpath");
                }
                String ddl = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                stmt.execute(ddl);
            }

            // Clean previous secondary indexes if any
            dropSecondaryIndexes(stmt);

            // 2. Seed 100,000 deterministic rows
            stmt.execute("TRUNCATE TABLE query_plan_trade");
            String seedSql = """
                    INSERT INTO query_plan_trade (id, buyer_id, status, created_at, payload)
                    SELECT
                        g AS id,
                        ((g - 1) % 1000) + 1 AS buyer_id,
                        CASE WHEN ((g - 1) / 1000) % 10 = 0 THEN 'PENDING' ELSE 'COMPLETED' END AS status,
                        TIMESTAMPTZ '2026-01-01 00:00:00+00' + (g * INTERVAL '1 second') AS created_at,
                        repeat(md5(g::text), 4) AS payload
                    FROM generate_series(1, 100000) g
                    """;
            stmt.execute(seedSql);

            // 3. Verify dataset assertions
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM query_plan_trade")) {
                assertTrue(rs.next());
                assertEquals(100000L, rs.getLong(1), "Dataset must contain exactly 100,000 rows");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(DISTINCT buyer_id) FROM query_plan_trade")) {
                assertTrue(rs.next());
                assertEquals(1000L, rs.getLong(1), "Dataset must contain exactly 1,000 distinct buyers");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM query_plan_trade WHERE status = 'PENDING'")) {
                assertTrue(rs.next());
                assertEquals(10000L, rs.getLong(1), "Dataset must contain exactly 10,000 PENDING trades");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM query_plan_trade WHERE status = 'COMPLETED'")) {
                assertTrue(rs.next());
                assertEquals(90000L, rs.getLong(1), "Dataset must contain exactly 90,000 COMPLETED trades");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM query_plan_trade WHERE buyer_id = 42")) {
                assertTrue(rs.next());
                assertEquals(100L, rs.getLong(1), "Buyer 42 must have exactly 100 total trades");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM query_plan_trade WHERE buyer_id = 42 AND status = 'PENDING'")) {
                assertTrue(rs.next());
                assertEquals(10L, rs.getLong(1), "Buyer 42 must have exactly 10 PENDING trades");
            }

            // Run ANALYZE to update statistics
            stmt.execute("ANALYZE query_plan_trade");
        }
    }

    private static Connection getLabConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", postgres.getUsername());
        props.setProperty("password", postgres.getPassword());
        props.setProperty("prepareThreshold", "0");
        Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), props);
        try (Statement s = conn.createStatement()) {
            s.execute("SET max_parallel_workers_per_gather = 0");
            s.execute("SET jit = off");
            s.execute("SET statement_timeout = '30s'");
        }
        return conn;
    }

    private static void dropSecondaryIndexes(Statement stmt) throws SQLException {
        stmt.execute("DROP INDEX IF EXISTS idx_qpt_buyer");
        stmt.execute("DROP INDEX IF EXISTS idx_qpt_buyer_status_recent");
        stmt.execute("DROP INDEX IF EXISTS idx_qpt_status");
    }

    private static String querySetting(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "UNKNOWN";
        }
    }

    private static Map<String, String> getIndexInventory(Statement stmt) throws SQLException {
        Map<String, String> inv = new LinkedHashMap<>();
        try (ResultSet rs = stmt.executeQuery("SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'query_plan_trade' ORDER BY indexname")) {
            while (rs.next()) {
                inv.put(rs.getString("indexname"), rs.getString("indexdef"));
            }
        }
        return inv;
    }

    @Test
    @DisplayName("EP1. No Secondary Index: Limit -> Sort -> Seq Scan")
    void ep1_noSecondaryIndex() throws Exception {
        try (Connection conn = getLabConnection();
             Statement stmt = conn.createStatement()) {

            dropSecondaryIndexes(stmt);
            stmt.execute("ANALYZE query_plan_trade");

            Map<String, String> indexes = getIndexInventory(stmt);
            assertEquals(1, indexes.size(), "Only primary key index should exist in EP1");
            assertTrue(indexes.containsKey("query_plan_trade_pkey"), "PK index must exist");

            // 1. Estimated-only EXPLAIN (FORMAT JSON)
            String explainOnlySql = "EXPLAIN (FORMAT JSON) " + Q1_SQL;
            JsonNode rawExplainOnly;
            try (PreparedStatement ps = conn.prepareStatement(explainOnlySql)) {
                ps.setInt(1, 42);
                ps.setString(2, "PENDING");
                ps.setInt(3, 5);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    rawExplainOnly = objectMapper.readTree(rs.getString(1));
                }
            }

            // 2. Warm-up & Business contract verification
            List<Long> actualIds = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(Q1_SQL)) {
                ps.setInt(1, 42);
                ps.setString(2, "PENDING");
                ps.setInt(3, 5);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        int buyerId = rs.getInt("buyer_id");
                        String status = rs.getString("status");
                        String payload = rs.getString("payload");
                        assertEquals(42, buyerId);
                        assertEquals("PENDING", status);
                        assertEquals(128, payload.length());
                        actualIds.add(id);
                    }
                }
            }
            assertEquals(EXPECTED_Q1_IDS, actualIds, "Q1 must return the 5 latest PENDING trade IDs for buyer 42");

            // 3. EXPLAIN (ANALYZE, BUFFERS, SETTINGS, TIMING OFF, FORMAT JSON)
            String explainAnalyzeSql = "EXPLAIN (ANALYZE, BUFFERS, SETTINGS, TIMING OFF, FORMAT JSON) " + Q1_SQL;
            JsonNode rawExplainAnalyze;
            try (PreparedStatement ps = conn.prepareStatement(explainAnalyzeSql)) {
                ps.setInt(1, 42);
                ps.setString(2, "PENDING");
                ps.setInt(3, 5);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    rawExplainAnalyze = objectMapper.readTree(rs.getString(1));
                }
            }

            ExplainPlanReader.PlanReport report = ExplainPlanReader.parse(rawExplainAnalyze);
            assertNotNull(report.rootNodeType(), "Root node must not be null");
            assertNotNull(report.nodeSummaries(), "Node summaries must not be null");
            assertFalse(report.nodeSummaries().isEmpty(), "Node summaries must not be empty");
            assertEquals(5L, report.actualRows(), "Query must output 5 actual rows");

            // Evaluate hypothesis decoupled from hard assertions
            boolean hypothesisMatched = "Limit".equals(report.rootNodeType())
                    && report.hasSortNode()
                    && "Seq Scan".equals(report.scanNodeType());

            String ep1Interpretation = hypothesisMatched
                    ? "Without a secondary index, PostgreSQL performs a full table Seq Scan, filters rows in heap, and sorts candidate rows before applying Limit."
                    : "Planner selected root=" + report.rootNodeType() + ", scan=" + report.scanNodeType() + ", sort=" + report.hasSortNode() + " based on cost model configuration.";

            Map<String, Object> resultSummary = new LinkedHashMap<>();
            resultSummary.put("rowCount", actualIds.size());
            resultSummary.put("validationPassed", true);
            resultSummary.put("returnedIds", actualIds);

            observations.put("EP1", new QueryPlanCaseObservation(
                    "EP1",
                    "Q1",
                    Q1_SQL.trim(),
                    Map.of("buyerId", 42, "status", "PENDING", "limit", 5),
                    new ArrayList<>(indexes.keySet()),
                    indexes,
                    resultSummary,
                    rawExplainAnalyze,
                    rawExplainOnly,
                    report.nodeSummaries(),
                    report,
                    "Limit -> Sort -> Seq Scan with heap filter discarding non-matching rows",
                    hypothesisMatched ? "MATCHED" : "DIFFERENT",
                    ep1Interpretation,
                    true
            ));
        }
    }

    @Test
    @DisplayName("EP2. Single-Column Index on buyer_id: Index/Bitmap Scan + Filter + Sort")
    void ep2_singleColumnBuyerIndex() throws Exception {
        try (Connection conn = getLabConnection();
             Statement stmt = conn.createStatement()) {

            dropSecondaryIndexes(stmt);
            stmt.execute("CREATE INDEX idx_qpt_buyer ON query_plan_trade(buyer_id)");
            stmt.execute("ANALYZE query_plan_trade");

            Map<String, String> indexes = getIndexInventory(stmt);
            assertTrue(indexes.containsKey("idx_qpt_buyer"), "Index idx_qpt_buyer must exist");

            // 1. Warm-up & Business contract verification
            List<Long> actualIds = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(Q1_SQL)) {
                ps.setInt(1, 42);
                ps.setString(2, "PENDING");
                ps.setInt(3, 5);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        actualIds.add(rs.getLong("id"));
                    }
                }
            }
            assertEquals(EXPECTED_Q1_IDS, actualIds, "EP2 must return the identical 5 IDs as EP1");

            // 2. EXPLAIN ANALYZE
            String explainAnalyzeSql = "EXPLAIN (ANALYZE, BUFFERS, SETTINGS, TIMING OFF, FORMAT JSON) " + Q1_SQL;
            JsonNode rawExplainAnalyze;
            try (PreparedStatement ps = conn.prepareStatement(explainAnalyzeSql)) {
                ps.setInt(1, 42);
                ps.setString(2, "PENDING");
                ps.setInt(3, 5);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    rawExplainAnalyze = objectMapper.readTree(rs.getString(1));
                }
            }

            ExplainPlanReader.PlanReport report = ExplainPlanReader.parse(rawExplainAnalyze);
            assertNotNull(report.rootNodeType(), "Root node must not be null");
            assertNotNull(report.nodeSummaries(), "Node summaries must not be null");
            assertFalse(report.nodeSummaries().isEmpty(), "Node summaries must not be empty");
            assertEquals(5L, report.actualRows(), "Query must output 5 actual rows");

            // Evaluate hypothesis decoupled from hard assertions
            boolean hypothesisMatched = report.hasSortNode()
                    && report.scanNodeType() != null
                    && (report.scanNodeType().contains("Index") || report.scanNodeType().contains("Bitmap"));

            String ep2Interpretation = hypothesisMatched
                    ? "Single-column index on buyer_id eliminates scanning unrelated buyers, but because rows are not sorted by created_at, a Sort node is still necessary."
                    : "Planner selected root=" + report.rootNodeType() + ", scan=" + report.scanNodeType() + ", sort=" + report.hasSortNode() + " based on cost model configuration.";

            Map<String, Object> resultSummary = new LinkedHashMap<>();
            resultSummary.put("rowCount", actualIds.size());
            resultSummary.put("validationPassed", true);
            resultSummary.put("returnedIds", actualIds);

            observations.put("EP2", new QueryPlanCaseObservation(
                    "EP2",
                    "Q1",
                    Q1_SQL.trim(),
                    Map.of("buyerId", 42, "status", "PENDING", "limit", 5),
                    new ArrayList<>(indexes.keySet()),
                    indexes,
                    resultSummary,
                    rawExplainAnalyze,
                    null,
                    report.nodeSummaries(),
                    report,
                    "Index Scan or Bitmap Scan on buyer_id narrows candidate rows, filters status, then sorts by created_at",
                    hypothesisMatched ? "MATCHED" : "DIFFERENT",
                    ep2Interpretation,
                    true
            ));
        }
    }

    @Test
    @DisplayName("EP3. Composite Index: Index Scan eliminates Sort node and stops at Limit")
    void ep3_compositeIndexMatchingFilterAndOrder() throws Exception {
        try (Connection conn = getLabConnection();
             Statement stmt = conn.createStatement()) {

            dropSecondaryIndexes(stmt);
            stmt.execute("CREATE INDEX idx_qpt_buyer_status_recent ON query_plan_trade(buyer_id, status, created_at DESC, id DESC)");
            stmt.execute("ANALYZE query_plan_trade");

            Map<String, String> indexes = getIndexInventory(stmt);
            assertTrue(indexes.containsKey("idx_qpt_buyer_status_recent"), "Composite index must exist");

            // 1. Warm-up & Business contract verification
            List<Long> actualIds = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(Q1_SQL)) {
                ps.setInt(1, 42);
                ps.setString(2, "PENDING");
                ps.setInt(3, 5);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        actualIds.add(rs.getLong("id"));
                    }
                }
            }
            assertEquals(EXPECTED_Q1_IDS, actualIds, "EP3 must return the identical 5 IDs as EP1 and EP2");

            // 2. EXPLAIN ANALYZE
            String explainAnalyzeSql = "EXPLAIN (ANALYZE, BUFFERS, SETTINGS, TIMING OFF, FORMAT JSON) " + Q1_SQL;
            JsonNode rawExplainAnalyze;
            try (PreparedStatement ps = conn.prepareStatement(explainAnalyzeSql)) {
                ps.setInt(1, 42);
                ps.setString(2, "PENDING");
                ps.setInt(3, 5);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    rawExplainAnalyze = objectMapper.readTree(rs.getString(1));
                }
            }

            ExplainPlanReader.PlanReport report = ExplainPlanReader.parse(rawExplainAnalyze);
            assertNotNull(report.rootNodeType(), "Root node must not be null");
            assertNotNull(report.nodeSummaries(), "Node summaries must not be null");
            assertFalse(report.nodeSummaries().isEmpty(), "Node summaries must not be empty");
            assertEquals(5L, report.actualRows(), "Query must output 5 actual rows");

            // Evaluate hypothesis decoupled from hard assertions
            boolean hypothesisMatched = !report.hasSortNode() && "Index Scan".equals(report.scanNodeType());

            String ep3Interpretation = hypothesisMatched
                    ? "The composite index covers both equality filters (buyer_id, status) and ordering columns (created_at DESC, id DESC), allowing the engine to read directly in sorted order and stop at Limit=5."
                    : "Planner selected root=" + report.rootNodeType() + ", scan=" + report.scanNodeType() + ", sort=" + report.hasSortNode() + " based on cost model configuration.";

            Map<String, Object> resultSummary = new LinkedHashMap<>();
            resultSummary.put("rowCount", actualIds.size());
            resultSummary.put("validationPassed", true);
            resultSummary.put("returnedIds", actualIds);

            observations.put("EP3", new QueryPlanCaseObservation(
                    "EP3",
                    "Q1",
                    Q1_SQL.trim(),
                    Map.of("buyerId", 42, "status", "PENDING", "limit", 5),
                    new ArrayList<>(indexes.keySet()),
                    indexes,
                    resultSummary,
                    rawExplainAnalyze,
                    null,
                    report.nodeSummaries(),
                    report,
                    "Index Scan using composite index traversing directly in descending created_at order without Sort",
                    hypothesisMatched ? "MATCHED" : "DIFFERENT",
                    ep3Interpretation,
                    true
            ));
        }
    }

    @Test
    @DisplayName("EP4. Status Index with Low Selectivity Query: Seq Scan preferred over Index Scan")
    void ep4_statusIndexLowSelectivityQuery() throws Exception {
        try (Connection conn = getLabConnection();
             Statement stmt = conn.createStatement()) {

            dropSecondaryIndexes(stmt);
            stmt.execute("CREATE INDEX idx_qpt_status ON query_plan_trade(status)");
            stmt.execute("ANALYZE query_plan_trade");

            Map<String, String> indexes = getIndexInventory(stmt);
            assertTrue(indexes.containsKey("idx_qpt_status"), "Index idx_qpt_status must exist");

            // 1. Warm-up & Business contract verification on Q2 (90,000 COMPLETED rows)
            int completedCount = 0;
            List<Long> sampleIds = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(Q2_SQL)) {
                ps.setString(1, "COMPLETED");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        completedCount++;
                        if (sampleIds.size() < 5) {
                            sampleIds.add(rs.getLong("id"));
                        }
                    }
                }
            }
            assertEquals(90000, completedCount, "Q2 must return exactly 90,000 COMPLETED trade rows");

            // 2. EXPLAIN ANALYZE
            String explainAnalyzeSql = "EXPLAIN (ANALYZE, BUFFERS, SETTINGS, TIMING OFF, FORMAT JSON) " + Q2_SQL;
            JsonNode rawExplainAnalyze;
            try (PreparedStatement ps = conn.prepareStatement(explainAnalyzeSql)) {
                ps.setString(1, "COMPLETED");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    rawExplainAnalyze = objectMapper.readTree(rs.getString(1));
                }
            }

            ExplainPlanReader.PlanReport report = ExplainPlanReader.parse(rawExplainAnalyze);
            assertEquals(90000L, report.actualRows(), "Report should indicate 90,000 actual rows");
            assertNotNull(report.scanNodeType());

            // Hypothesis: Seq Scan is preferred because 90% rows match and payload requires heap lookups
            boolean hypothesisMatched = "Seq Scan".equalsIgnoreCase(report.scanNodeType());

            Map<String, Object> resultSummary = new LinkedHashMap<>();
            resultSummary.put("rowCount", completedCount);
            resultSummary.put("validationPassed", true);
            resultSummary.put("sampleIds", sampleIds);

            observations.put("EP4", new QueryPlanCaseObservation(
                    "EP4",
                    "Q2",
                    Q2_SQL.trim(),
                    Map.of("status", "COMPLETED"),
                    new ArrayList<>(indexes.keySet()),
                    indexes,
                    resultSummary,
                    rawExplainAnalyze,
                    null,
                    report.nodeSummaries(),
                    report,
                    "Seq Scan preferred over Index Scan due to 90% selectivity and heap page traversal cost",
                    hypothesisMatched ? "MATCHED" : "DIFFERENT",
                    hypothesisMatched
                            ? "With 90% of rows matching and a wide payload column, the cost of random I/O via index lookups exceeds sequential scanning of table heap pages."
                            : "Planner selected " + report.scanNodeType() + " based on cost model configuration and statistics.",
                    true
            ));
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

        File file = new File(dir, "query-plan-observations.json");
        String runId = System.getProperty("lab.runId", "ad-hoc-" + Instant.now().toEpochMilli());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1.0.0");
        payload.put("runId", runId);
        payload.put("timestamp", Instant.now().toString());

        Map<String, Object> dbInfo = new LinkedHashMap<>(runtimeSettings);
        payload.put("database", dbInfo);

        Map<String, Object> datasetInfo = new LinkedHashMap<>();
        datasetInfo.put("tableName", "query_plan_trade");
        datasetInfo.put("totalRows", 100000);
        datasetInfo.put("distinctBuyers", 1000);
        datasetInfo.put("pendingTrades", 10000);
        datasetInfo.put("completedTrades", 90000);
        datasetInfo.put("seedFormula", "id=g, buyer_id=((g-1)%1000)+1, status=CASE WHEN ((g-1)/1000)%10=0 THEN PENDING ELSE COMPLETED END, created_at=2026-01-01+g*1s, payload=repeat(md5(g),4)");
        payload.put("dataset", datasetInfo);

        List<String> keys = new ArrayList<>(observations.keySet());
        Collections.sort(keys);
        List<QueryPlanCaseObservation> scenarios = new ArrayList<>();
        for (String k : keys) {
            scenarios.add(observations.get(k));
        }
        payload.put("scenarios", scenarios);

        try {
            objectMapper.writeValue(file, payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to export query plan observations to " + file.getAbsolutePath(), e);
        }
    }

    public record QueryPlanCaseObservation(
            String caseId,
            String queryId,
            String sqlTemplate,
            Map<String, Object> params,
            List<String> actualIndexInventory,
            Map<String, String> indexDefinitions,
            Map<String, Object> resultSummary,
            JsonNode rawExplainAnalyzeJson,
            JsonNode rawExplainOnlyJson,
            List<ExplainPlanReader.PlanNodeSummary> nodeSummaries,
            ExplainPlanReader.PlanReport rootMetrics,
            String hypothesis,
            String hypothesisStatus,
            String interpretation,
            boolean assertionsPassed
    ) {}
}
