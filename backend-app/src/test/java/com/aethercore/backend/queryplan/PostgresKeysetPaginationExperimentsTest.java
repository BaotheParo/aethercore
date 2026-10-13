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
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2-E5B: OFFSET vs Keyset Pagination Experiments.
 *
 * Scenarios:
 * - KP1: Same deep page: OFFSET vs Keyset execution plans (100k rows, page size 20, deep offset 80,000).
 * - KP2: Duplicate timestamp trap: single-column predicate vs composite tuple cursor (24 rows, page size 7).
 * - KP3: Concurrent INSERT between pages: OFFSET duplicates row, Keyset remains stable (24 rows, page size 5).
 * - KP4: Concurrent DELETE before boundary: OFFSET skips unread row, Keyset remains stable (24 rows, page size 5).
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.MethodName.class)
public class PostgresKeysetPaginationExperimentsTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aethercore_keyset_test")
            .withUsername("test")
            .withPassword("test");

    private static final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static final String FIRST_PAGE_SQL = """
            SELECT id, buyer_id, created_at, payload
            FROM keyset_trade
            WHERE buyer_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT ?
            """;

    private static final String OFFSET_SQL = """
            SELECT id, buyer_id, created_at, payload
            FROM keyset_trade
            WHERE buyer_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT ? OFFSET ?
            """;

    private static final String KEYSET_SQL = """
            SELECT id, buyer_id, created_at, payload
            FROM keyset_trade
            WHERE buyer_id = ?
              AND (created_at, id) < (?, ?)
            ORDER BY created_at DESC, id DESC
            LIMIT ?
            """;

    private static String postgresServerVersion;
    private static String jdbcDriverVersion;
    private static final Map<String, Object> runtimeSettings = new LinkedHashMap<>();
    private static final Map<String, Object> observations = new ConcurrentHashMap<>();

    public record TradeCursor(OffsetDateTime createdAt, long id) {}

    public record TradeRow(long id, long buyerId, OffsetDateTime createdAt, String payload) {}

    @BeforeAll
    static void setUpAll() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", postgres.getUsername());
        props.setProperty("password", postgres.getPassword());
        props.setProperty("prepareThreshold", "0");

        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), props);
             Statement stmt = conn.createStatement()) {

            stmt.execute("SET max_parallel_workers_per_gather = 0");
            stmt.execute("SET jit = off");
            stmt.execute("SET statement_timeout = '30s'");

            DatabaseMetaData meta = conn.getMetaData();
            jdbcDriverVersion = meta.getDriverVersion();

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

            // Initialize probe table and index
            try (InputStream is = PostgresKeysetPaginationExperimentsTest.class.getResourceAsStream("/sql/keyset-pagination-probe.sql")) {
                if (is == null) {
                    throw new IllegalStateException("Resource /sql/keyset-pagination-probe.sql not found on classpath");
                }
                String ddl = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                stmt.execute(ddl);
            }
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

    private static String querySetting(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "UNKNOWN";
        }
    }

    private static void seedLargeFixture(Statement stmt) throws SQLException {
        stmt.execute("TRUNCATE TABLE keyset_trade");
        String sql = """
                INSERT INTO keyset_trade (id, buyer_id, created_at, payload)
                SELECT g, 42,
                       TIMESTAMPTZ '2026-01-01 00:00:00+00'
                           + (((g - 1) / 10) * INTERVAL '1 second'),
                       repeat(md5(g::text), 4)
                FROM generate_series(1, 100000) g
                """;
        stmt.execute(sql);

        // Optional non-matching buyer rows to prove buyer filter isolation
        stmt.execute("""
                INSERT INTO keyset_trade (id, buyer_id, created_at, payload)
                SELECT 100000 + g, 99,
                       TIMESTAMPTZ '2026-01-01 00:00:00+00' + (g * INTERVAL '1 second'),
                       repeat(md5((100000 + g)::text), 4)
                FROM generate_series(1, 5) g
                """);

        stmt.execute("ANALYZE keyset_trade");
    }

    private static void seedSmallFixture(Statement stmt) throws SQLException {
        stmt.execute("TRUNCATE TABLE keyset_trade");
        String sql = """
                INSERT INTO keyset_trade (id, buyer_id, created_at, payload)
                SELECT g, 42,
                       TIMESTAMPTZ '2026-01-01 00:00:00+00'
                           + (((g - 1) / 10) * INTERVAL '1 second'),
                       repeat(md5(g::text), 4)
                FROM generate_series(1, 24) g
                """;
        stmt.execute(sql);

        // Non-matching buyer 99 rows
        stmt.execute("""
                INSERT INTO keyset_trade (id, buyer_id, created_at, payload)
                VALUES (101, 99, TIMESTAMPTZ '2026-01-01 00:00:10+00', repeat(md5('101'), 4)),
                       (102, 99, TIMESTAMPTZ '2026-01-01 00:00:11+00', repeat(md5('102'), 4))
                """);

        stmt.execute("ANALYZE keyset_trade");
    }

    private static List<TradeRow> executeQuery(PreparedStatement ps) throws SQLException {
        List<TradeRow> rows = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long id = rs.getLong("id");
                long buyerId = rs.getLong("buyer_id");
                OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
                String payload = rs.getString("payload");
                rows.add(new TradeRow(id, buyerId, createdAt, payload));
            }
        }
        return rows;
    }

    private static List<Long> extractIds(List<TradeRow> rows) {
        List<Long> ids = new ArrayList<>(rows.size());
        for (TradeRow r : rows) {
            ids.add(r.id());
        }
        return ids;
    }

    @Test
    @DisplayName("KP1. Same Deep Page: OFFSET vs Keyset Execution Plans")
    void kp1_sameDeepPageComparison() throws Exception {
        try (Connection conn = getLabConnection();
             Statement stmt = conn.createStatement()) {

            seedLargeFixture(stmt);

            // Assert baseline fixture invariants
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM keyset_trade WHERE buyer_id = 42")) {
                assertTrue(rs.next());
                assertEquals(100000L, rs.getLong(1));
            }

            int pageSize = 20;
            int deepOffset = 80000;

            // Independent oracles generated directly from deterministic fixture formulas
            List<Long> expectedFirstPageIds = new ArrayList<>(pageSize);
            for (long id = 100000L; id >= 99981L; id--) {
                expectedFirstPageIds.add(id);
            }
            List<Long> expectedDeepPageIds = new ArrayList<>(pageSize);
            for (long id = 20000L; id >= 19981L; id--) {
                expectedDeepPageIds.add(id);
            }

            // 1. Measurement 1: Baseline First Page (OFFSET 0)
            List<TradeRow> firstPageRows;
            try (PreparedStatement ps = conn.prepareStatement(FIRST_PAGE_SQL)) {
                ps.setLong(1, 42L);
                ps.setInt(2, pageSize);
                firstPageRows = executeQuery(ps);
            }
            assertEquals(20, firstPageRows.size());
            List<Long> firstPageIds = extractIds(firstPageRows);
            assertEquals(expectedFirstPageIds, firstPageIds, "First page must match independent oracle [100000..99981]");
            for (TradeRow r : firstPageRows) {
                assertEquals(42L, r.buyerId(), "First page row must belong to buyer 42");
                assertNotNull(r.payload(), "Payload must be present");
                assertNotNull(r.createdAt(), "CreatedAt must be present");
            }

            // Explain First Page
            String explainFirstSql = "EXPLAIN (ANALYZE, BUFFERS, SETTINGS, TIMING OFF, FORMAT JSON) " + FIRST_PAGE_SQL;
            JsonNode rawExplainFirst;
            try (PreparedStatement ps = conn.prepareStatement(explainFirstSql)) {
                ps.setLong(1, 42L);
                ps.setInt(2, pageSize);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    rawExplainFirst = objectMapper.readTree(rs.getString(1));
                }
            }
            ExplainPlanReader.PlanReport firstReport = ExplainPlanReader.parse(rawExplainFirst);

            // 2. Measurement 2: Deep OFFSET 80.000
            List<TradeRow> deepOffsetRows;
            try (PreparedStatement ps = conn.prepareStatement(OFFSET_SQL)) {
                ps.setLong(1, 42L);
                ps.setInt(2, pageSize);
                ps.setInt(3, deepOffset);
                deepOffsetRows = executeQuery(ps);
            }
            assertEquals(20, deepOffsetRows.size());
            List<Long> deepOffsetIds = extractIds(deepOffsetRows);
            assertEquals(expectedDeepPageIds, deepOffsetIds, "Deep OFFSET must match independent oracle [20000..19981]");
            for (TradeRow r : deepOffsetRows) {
                assertEquals(42L, r.buyerId(), "Deep offset row must belong to buyer 42");
                assertNotNull(r.payload(), "Payload must be present");
                assertNotNull(r.createdAt(), "CreatedAt must be present");
            }

            // Explain Deep OFFSET
            String explainOffsetSql = "EXPLAIN (ANALYZE, BUFFERS, SETTINGS, TIMING OFF, FORMAT JSON) " + OFFSET_SQL;
            JsonNode rawExplainOffset;
            try (PreparedStatement ps = conn.prepareStatement(explainOffsetSql)) {
                ps.setLong(1, 42L);
                ps.setInt(2, pageSize);
                ps.setInt(3, deepOffset);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    rawExplainOffset = objectMapper.readTree(rs.getString(1));
                }
            }
            ExplainPlanReader.PlanReport offsetReport = ExplainPlanReader.parse(rawExplainOffset);

            // 3. Measurement 3: Deep Keyset with boundary row ID 20001
            TradeCursor boundaryCursor;
            try (PreparedStatement ps = conn.prepareStatement("SELECT created_at, id FROM keyset_trade WHERE buyer_id = 42 AND id = 20001")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    boundaryCursor = new TradeCursor(rs.getObject("created_at", OffsetDateTime.class), rs.getLong("id"));
                }
            }
            assertEquals(20001L, boundaryCursor.id());

            List<TradeRow> deepKeysetRows;
            try (PreparedStatement ps = conn.prepareStatement(KEYSET_SQL)) {
                ps.setLong(1, 42L);
                ps.setObject(2, boundaryCursor.createdAt());
                ps.setLong(3, boundaryCursor.id());
                ps.setInt(4, pageSize);
                deepKeysetRows = executeQuery(ps);
            }
            assertEquals(20, deepKeysetRows.size());
            List<Long> deepKeysetIds = extractIds(deepKeysetRows);
            assertEquals(expectedDeepPageIds, deepKeysetIds, "Deep Keyset must match independent oracle [20000..19981]");
            for (TradeRow r : deepKeysetRows) {
                assertEquals(42L, r.buyerId(), "Deep keyset row must belong to buyer 42");
                assertNotNull(r.payload(), "Payload must be present");
                assertNotNull(r.createdAt(), "CreatedAt must be present");
            }

            // Verify Semantic Invariant: deep OFFSET IDs match deep Keyset IDs exactly
            assertEquals(deepOffsetIds, deepKeysetIds, "Deep OFFSET and deep Keyset must return identical IDs in the same order");

            // Explain Deep Keyset
            String explainKeysetSql = "EXPLAIN (ANALYZE, BUFFERS, SETTINGS, TIMING OFF, FORMAT JSON) " + KEYSET_SQL;
            JsonNode rawExplainKeyset;
            try (PreparedStatement ps = conn.prepareStatement(explainKeysetSql)) {
                ps.setLong(1, 42L);
                ps.setObject(2, boundaryCursor.createdAt());
                ps.setLong(3, boundaryCursor.id());
                ps.setInt(4, pageSize);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    rawExplainKeyset = objectMapper.readTree(rs.getString(1));
                }
            }
            ExplainPlanReader.PlanReport keysetReport = ExplainPlanReader.parse(rawExplainKeyset);

            // Inspect specific scan nodes on table 'keyset_trade'
            ExplainPlanReader.PlanNodeSummary offsetScanNode = findTableScanNode(offsetReport, "keyset_trade");
            ExplainPlanReader.PlanNodeSummary keysetScanNode = findTableScanNode(keysetReport, "keyset_trade");

            long offsetScanRows = (offsetScanNode != null && offsetScanNode.actualRows() != null) ? offsetScanNode.actualRows() : -1L;
            long keysetScanRows = (keysetScanNode != null && keysetScanNode.actualRows() != null) ? keysetScanNode.actualRows() : -1L;
            long offsetScanLoops = (offsetScanNode != null && offsetScanNode.actualLoops() != null) ? offsetScanNode.actualLoops() : -1L;
            long keysetScanLoops = (keysetScanNode != null && keysetScanNode.actualLoops() != null) ? keysetScanNode.actualLoops() : -1L;

            boolean hasTupleBoundaryInIndexCond = keysetScanNode != null && keysetScanNode.indexCond() != null
                    && (keysetScanNode.indexCond().contains("created_at") || keysetScanNode.indexCond().contains("ROW("));

            // Specific hypothesis: ordered Index Scan using idx_kst_buyer_recent where deep OFFSET scans 80,020 rows (loops=1) and keyset scans 20 rows (loops=1)
            boolean hypothesisMatched = offsetScanNode != null && keysetScanNode != null
                    && "Index Scan".equalsIgnoreCase(offsetScanNode.nodeType())
                    && "Index Scan".equalsIgnoreCase(keysetScanNode.nodeType())
                    && "idx_kst_buyer_recent".equalsIgnoreCase(offsetScanNode.indexName())
                    && "idx_kst_buyer_recent".equalsIgnoreCase(keysetScanNode.indexName())
                    && hasTupleBoundaryInIndexCond
                    && offsetScanRows == 80020L && keysetScanRows == 20L
                    && offsetScanLoops == 1L && keysetScanLoops == 1L;

            Map<String, Object> measurements = new LinkedHashMap<>();
            measurements.put("first_page", Map.of(
                    "sql", FIRST_PAGE_SQL.trim(),
                    "params", Map.of("buyerId", 42, "limit", pageSize),
                    "returnedIds", firstPageIds,
                    "rawExplainAnalyzeJson", rawExplainFirst,
                    "report", firstReport
            ));
            measurements.put("deep_offset", Map.of(
                    "sql", OFFSET_SQL.trim(),
                    "params", Map.of("buyerId", 42, "limit", pageSize, "offset", deepOffset),
                    "returnedIds", deepOffsetIds,
                    "rawExplainAnalyzeJson", rawExplainOffset,
                    "report", offsetReport,
                    "scanDeliveredRows", offsetScanRows
            ));
            measurements.put("deep_keyset", Map.of(
                    "sql", KEYSET_SQL.trim(),
                    "params", Map.of("buyerId", 42, "cursorTime", boundaryCursor.createdAt().toString(), "cursorId", boundaryCursor.id(), "limit", pageSize),
                    "cursorSource", "fixture-known-boundary",
                    "returnedIds", deepKeysetIds,
                    "rawExplainAnalyzeJson", rawExplainKeyset,
                    "report", keysetReport,
                    "scanDeliveredRows", keysetScanRows
            ));

            Map<String, Object> kp1Obs = new LinkedHashMap<>();
            kp1Obs.put("caseId", "KP1");
            kp1Obs.put("description", "Same deep page: OFFSET vs Keyset execution plans");
            kp1Obs.put("pageSize", pageSize);
            kp1Obs.put("fixtureRows", 100005);
            kp1Obs.put("fixtureRowsBuyer42", 100000);
            kp1Obs.put("fixtureRowsBuyer99", 5);
            kp1Obs.put("expectedIds", expectedDeepPageIds);
            kp1Obs.put("returnedIds", Map.of(
                    "firstPage", firstPageIds,
                    "deepOffset", deepOffsetIds,
                    "deepKeyset", deepKeysetIds
            ));
            kp1Obs.put("deepPagesEqual", true);
            kp1Obs.put("measurements", measurements);
            kp1Obs.put("hypothesis", "Deep OFFSET scans exactly 80,020 rows (loops=1) discarding 80,000 in Limit, while Keyset uses boundary in Index Cond scanning exactly 20 rows (loops=1)");
            kp1Obs.put("hypothesisStatus", hypothesisMatched ? "MATCHED" : "DIFFERENT");
            kp1Obs.put("interpretation", hypothesisMatched
                    ? "Deep OFFSET forced index scan idx_kst_buyer_recent to read through 80,020 rows before Limit discarded 80,000, whereas Keyset sought directly to the cursor boundary and scanned only 20 rows."
                    : String.format("Planner executed with offsetScanNode=%s (rows=%d, loops=%d), keysetScanNode=%s (rows=%d, loops=%d, cond=%s).",
                            offsetScanNode != null ? offsetScanNode.nodeType() : "NONE", offsetScanRows, offsetScanLoops,
                            keysetScanNode != null ? keysetScanNode.nodeType() : "NONE", keysetScanRows, keysetScanLoops,
                            keysetScanNode != null ? keysetScanNode.indexCond() : "NONE"));
            kp1Obs.put("assertionsPassed", true);

            observations.put("KP1", kp1Obs);
        }
    }

    private static ExplainPlanReader.PlanNodeSummary findTableScanNode(ExplainPlanReader.PlanReport report, String relation) {
        if (report == null || report.nodeSummaries() == null) {
            return null;
        }
        for (ExplainPlanReader.PlanNodeSummary s : report.nodeSummaries()) {
            if (s.nodeType() != null && s.nodeType().toLowerCase().contains("scan")) {
                if (relation == null || relation.equalsIgnoreCase(s.relationName())) {
                    return s;
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("KP2. Duplicate Timestamp Trap: Single-Column vs Tuple Cursor")
    void kp2_duplicateTimestampTrap() throws Exception {
        try (Connection conn = getLabConnection();
             Statement stmt = conn.createStatement()) {

            seedSmallFixture(stmt);
            int pageSize = 7;

            // 1. Read Page 1 (LIMIT 7)
            List<TradeRow> page1Rows;
            try (PreparedStatement ps = conn.prepareStatement(FIRST_PAGE_SQL)) {
                ps.setLong(1, 42L);
                ps.setInt(2, pageSize);
                page1Rows = executeQuery(ps);
            }
            assertEquals(7, page1Rows.size());
            List<Long> page1Ids = extractIds(page1Rows);
            List<Long> expectedPage1 = List.of(24L, 23L, 22L, 21L, 20L, 19L, 18L);
            assertEquals(expectedPage1, page1Ids);

            TradeRow lastRow = page1Rows.get(page1Rows.size() - 1);
            TradeCursor page1Cursor = new TradeCursor(lastRow.createdAt(), lastRow.id());
            assertEquals(18L, page1Cursor.id());

            // 2. Broken Page 2: Single-column created_at < ?
            String brokenSql = """
                    SELECT id, buyer_id, created_at, payload
                    FROM keyset_trade
                    WHERE buyer_id = ?
                      AND created_at < ?
                    ORDER BY created_at DESC, id DESC
                    LIMIT ?
                    """;
            List<TradeRow> brokenRows;
            try (PreparedStatement ps = conn.prepareStatement(brokenSql)) {
                ps.setLong(1, 42L);
                ps.setObject(2, page1Cursor.createdAt());
                ps.setInt(3, pageSize);
                brokenRows = executeQuery(ps);
            }
            List<Long> brokenPage2Ids = extractIds(brokenRows);
            // Single-column predicate skips rows with created_at == page1Cursor.createdAt() (IDs 17..11)
            List<Long> expectedBrokenIds = List.of(10L, 9L, 8L, 7L, 6L, 5L, 4L);
            assertEquals(expectedBrokenIds, brokenPage2Ids, "Broken single-column query skips tie group rows 17..11");

            List<Long> skippedIds = List.of(17L, 16L, 15L, 14L, 13L, 12L, 11L);

            // 3. Correct Page 2: Composite tuple cursor (created_at, id) < (?, ?)
            List<TradeRow> correctRows;
            try (PreparedStatement ps = conn.prepareStatement(KEYSET_SQL)) {
                ps.setLong(1, 42L);
                ps.setObject(2, page1Cursor.createdAt());
                ps.setLong(3, page1Cursor.id());
                ps.setInt(4, pageSize);
                correctRows = executeQuery(ps);
            }
            List<Long> correctPage2Ids = extractIds(correctRows);
            assertEquals(skippedIds, correctPage2Ids, "Correct tuple query fetches tie group rows 17..11 seamlessly");

            // 4. Complete traversal using correct tuple cursor across all pages
            List<Long> allCollectedIds = new ArrayList<>();
            List<Integer> pageSizes = new ArrayList<>();
            TradeCursor currentCursor = null;
            int iterationCount = 0;
            int maxIterations = 10;

            while (iterationCount++ < maxIterations) {
                List<TradeRow> page;
                if (currentCursor == null) {
                    try (PreparedStatement ps = conn.prepareStatement(FIRST_PAGE_SQL)) {
                        ps.setLong(1, 42L);
                        ps.setInt(2, pageSize);
                        page = executeQuery(ps);
                    }
                } else {
                    try (PreparedStatement ps = conn.prepareStatement(KEYSET_SQL)) {
                        ps.setLong(1, 42L);
                        ps.setObject(2, currentCursor.createdAt());
                        ps.setLong(3, currentCursor.id());
                        ps.setInt(4, pageSize);
                        page = executeQuery(ps);
                    }
                }

                if (page.isEmpty()) {
                    break;
                }

                pageSizes.add(page.size());
                for (TradeRow r : page) {
                    allCollectedIds.add(r.id());
                }

                TradeRow last = page.get(page.size() - 1);
                currentCursor = new TradeCursor(last.createdAt(), last.id());
            }

            assertEquals(List.of(7, 7, 7, 3), pageSizes, "Pages should have sizes 7, 7, 7, 3");
            assertEquals(24, allCollectedIds.size());
            List<Long> expectedAll = new ArrayList<>();
            for (long i = 24; i >= 1; i--) {
                expectedAll.add(i);
            }
            assertEquals(expectedAll, allCollectedIds, "Full traversal must yield exactly 24..1 without duplicates or missing rows");

            Map<String, Object> kp2Obs = new LinkedHashMap<>();
            kp2Obs.put("caseId", "KP2");
            kp2Obs.put("description", "Duplicate timestamp trap: single-column vs tuple cursor");
            kp2Obs.put("pageSize", pageSize);
            kp2Obs.put("fixtureRows", 24);
            kp2Obs.put("page1Ids", page1Ids);
            kp2Obs.put("page1Cursor", Map.of("createdAt", page1Cursor.createdAt().toString(), "id", page1Cursor.id()));
            kp2Obs.put("brokenPage2Ids", brokenPage2Ids);
            kp2Obs.put("skippedIds", skippedIds);
            kp2Obs.put("correctPage2Ids", correctPage2Ids);
            kp2Obs.put("fullTraversalIds", allCollectedIds);
            kp2Obs.put("fullTraversalPageSizes", pageSizes);
            kp2Obs.put("assertionsPassed", true);

            observations.put("KP2", kp2Obs);
        }
    }

    @Test
    @DisplayName("KP3. Concurrent INSERT: OFFSET Duplicates Row, Keyset Remains Stable")
    void kp3_concurrentInsertDrift() throws Exception {
        try (Connection readerConn = getLabConnection();
             Connection writerConn = getLabConnection();
             Statement stmt = readerConn.createStatement()) {

            seedSmallFixture(stmt);
            int pageSize = 5;

            // 1. Reader reads Page 1 (LIMIT 5)
            List<TradeRow> page1Rows;
            try (PreparedStatement ps = readerConn.prepareStatement(FIRST_PAGE_SQL)) {
                ps.setLong(1, 42L);
                ps.setInt(2, pageSize);
                page1Rows = executeQuery(ps);
            }
            assertEquals(5, page1Rows.size());
            List<Long> page1Ids = extractIds(page1Rows);
            List<Long> expectedPage1 = List.of(24L, 23L, 22L, 21L, 20L);
            assertEquals(expectedPage1, page1Ids);

            TradeRow lastRow = page1Rows.get(page1Rows.size() - 1);
            TradeCursor page1Cursor = new TradeCursor(lastRow.createdAt(), lastRow.id());
            assertEquals(20L, page1Cursor.id());

            // 2. Writer inserts ID 25 with newer timestamp (base + 3 seconds) and commits
            writerConn.setAutoCommit(false);
            try (PreparedStatement ps = writerConn.prepareStatement(
                    "INSERT INTO keyset_trade (id, buyer_id, created_at, payload) VALUES (25, 42, TIMESTAMPTZ '2026-01-01 00:00:03+00', repeat(md5('25'), 4))")) {
                ps.executeUpdate();
            }
            writerConn.commit();
            writerConn.setAutoCommit(true);

            // 3. Reader reads Page 2 via OFFSET 5
            List<TradeRow> offsetPage2Rows;
            try (PreparedStatement ps = readerConn.prepareStatement(OFFSET_SQL)) {
                ps.setLong(1, 42L);
                ps.setInt(2, pageSize);
                ps.setInt(3, 5);
                offsetPage2Rows = executeQuery(ps);
            }
            List<Long> offsetPage2Ids = extractIds(offsetPage2Rows);
            // With ID 25 added at the head, row 20 is shifted to index 5, causing OFFSET 5 to return row 20 AGAIN
            List<Long> expectedOffsetPage2 = List.of(20L, 19L, 18L, 17L, 16L);
            assertEquals(expectedOffsetPage2, offsetPage2Ids);
            assertTrue(offsetPage2Ids.contains(20L), "OFFSET 5 duplicates ID 20 from page 1");

            // 4. Reader reads Page 2 via Keyset cursor of ID 20
            List<TradeRow> keysetPage2Rows;
            try (PreparedStatement ps = readerConn.prepareStatement(KEYSET_SQL)) {
                ps.setLong(1, 42L);
                ps.setObject(2, page1Cursor.createdAt());
                ps.setLong(3, page1Cursor.id());
                ps.setInt(4, pageSize);
                keysetPage2Rows = executeQuery(ps);
            }
            List<Long> keysetPage2Ids = extractIds(keysetPage2Rows);
            List<Long> expectedKeysetPage2 = List.of(19L, 18L, 17L, 16L, 15L);
            assertEquals(expectedKeysetPage2, keysetPage2Ids);
            assertFalse(keysetPage2Ids.contains(20L), "Keyset pagination does not duplicate ID 20");
            assertFalse(keysetPage2Ids.contains(25L), "Keyset pagination is bounded by cursor and ignores newer inserted row 25");

            Map<String, Object> kp3Obs = new LinkedHashMap<>();
            kp3Obs.put("caseId", "KP3");
            kp3Obs.put("description", "Concurrent INSERT between pages: OFFSET duplicates row, Keyset stable");
            kp3Obs.put("pageSize", pageSize);
            kp3Obs.put("isolation", "READ COMMITTED");
            kp3Obs.put("readerIsolation", readerConn.getTransactionIsolation() == Connection.TRANSACTION_READ_COMMITTED ? "READ COMMITTED" : "LEVEL_" + readerConn.getTransactionIsolation());
            kp3Obs.put("readerAutoCommit", readerConn.getAutoCommit());
            kp3Obs.put("transactionModel", "per-statement auto-commit in READ COMMITTED; writer committed before reader second query");
            kp3Obs.put("mutation", "INSERT id=25 with newer timestamp base+3s");
            kp3Obs.put("page1Ids", page1Ids);
            kp3Obs.put("page1Cursor", Map.of("createdAt", page1Cursor.createdAt().toString(), "id", page1Cursor.id()));
            kp3Obs.put("offsetPage2Ids", offsetPage2Ids);
            kp3Obs.put("offsetDuplicateId", 20L);
            kp3Obs.put("keysetPage2Ids", keysetPage2Ids);
            kp3Obs.put("keysetHasDuplicate", false);
            kp3Obs.put("assertionsPassed", true);

            observations.put("KP3", kp3Obs);
        }
    }

    @Test
    @DisplayName("KP4. Concurrent DELETE: OFFSET Skips Unread Row, Keyset Remains Stable")
    void kp4_concurrentDeleteDrift() throws Exception {
        try (Connection readerConn = getLabConnection();
             Connection writerConn = getLabConnection();
             Statement stmt = readerConn.createStatement()) {

            seedSmallFixture(stmt);
            int pageSize = 5;

            // 1. Reader reads Page 1 (LIMIT 5)
            List<TradeRow> page1Rows;
            try (PreparedStatement ps = readerConn.prepareStatement(FIRST_PAGE_SQL)) {
                ps.setLong(1, 42L);
                ps.setInt(2, pageSize);
                page1Rows = executeQuery(ps);
            }
            assertEquals(5, page1Rows.size());
            List<Long> page1Ids = extractIds(page1Rows);
            List<Long> expectedPage1 = List.of(24L, 23L, 22L, 21L, 20L);
            assertEquals(expectedPage1, page1Ids);

            TradeRow lastRow = page1Rows.get(page1Rows.size() - 1);
            TradeCursor page1Cursor = new TradeCursor(lastRow.createdAt(), lastRow.id());
            assertEquals(20L, page1Cursor.id());

            // 2. Writer deletes row 24 and commits
            writerConn.setAutoCommit(false);
            try (PreparedStatement ps = writerConn.prepareStatement("DELETE FROM keyset_trade WHERE buyer_id = 42 AND id = 24")) {
                ps.executeUpdate();
            }
            writerConn.commit();
            writerConn.setAutoCommit(true);

            // 3. Reader reads Page 2 via OFFSET 5
            List<TradeRow> offsetPage2Rows;
            try (PreparedStatement ps = readerConn.prepareStatement(OFFSET_SQL)) {
                ps.setLong(1, 42L);
                ps.setInt(2, pageSize);
                ps.setInt(3, 5);
                offsetPage2Rows = executeQuery(ps);
            }
            List<Long> offsetPage2Ids = extractIds(offsetPage2Rows);
            // With ID 24 deleted, row 19 is shifted to index 4, so OFFSET 5 skips row 19 completely!
            List<Long> expectedOffsetPage2 = List.of(18L, 17L, 16L, 15L, 14L);
            assertEquals(expectedOffsetPage2, offsetPage2Ids);
            assertFalse(offsetPage2Ids.contains(19L), "OFFSET 5 completely skips unread ID 19");

            // 4. Reader reads Page 2 via Keyset cursor of ID 20
            List<TradeRow> keysetPage2Rows;
            try (PreparedStatement ps = readerConn.prepareStatement(KEYSET_SQL)) {
                ps.setLong(1, 42L);
                ps.setObject(2, page1Cursor.createdAt());
                ps.setLong(3, page1Cursor.id());
                ps.setInt(4, pageSize);
                keysetPage2Rows = executeQuery(ps);
            }
            List<Long> keysetPage2Ids = extractIds(keysetPage2Rows);
            List<Long> expectedKeysetPage2 = List.of(19L, 18L, 17L, 16L, 15L);
            assertEquals(expectedKeysetPage2, keysetPage2Ids);
            assertTrue(keysetPage2Ids.contains(19L), "Keyset pagination does not skip ID 19");

            Map<String, Object> kp4Obs = new LinkedHashMap<>();
            kp4Obs.put("caseId", "KP4");
            kp4Obs.put("description", "Concurrent DELETE before boundary: OFFSET skips row, Keyset stable");
            kp4Obs.put("pageSize", pageSize);
            kp4Obs.put("isolation", "READ COMMITTED");
            kp4Obs.put("readerIsolation", readerConn.getTransactionIsolation() == Connection.TRANSACTION_READ_COMMITTED ? "READ COMMITTED" : "LEVEL_" + readerConn.getTransactionIsolation());
            kp4Obs.put("readerAutoCommit", readerConn.getAutoCommit());
            kp4Obs.put("transactionModel", "per-statement auto-commit in READ COMMITTED; writer committed before reader second query");
            kp4Obs.put("mutation", "DELETE id=24");
            kp4Obs.put("page1Ids", page1Ids);
            kp4Obs.put("page1Cursor", Map.of("createdAt", page1Cursor.createdAt().toString(), "id", page1Cursor.id()));
            kp4Obs.put("offsetPage2Ids", offsetPage2Ids);
            kp4Obs.put("offsetSkippedId", 19L);
            kp4Obs.put("keysetPage2Ids", keysetPage2Ids);
            kp4Obs.put("keysetHasSkipped", false);
            kp4Obs.put("assertionsPassed", true);

            observations.put("KP4", kp4Obs);
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

        File file = new File(dir, "keyset-pagination-observations.json");
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
            throw new RuntimeException("Failed to export keyset pagination observations to " + file.getAbsolutePath(), e);
        }
    }
}
