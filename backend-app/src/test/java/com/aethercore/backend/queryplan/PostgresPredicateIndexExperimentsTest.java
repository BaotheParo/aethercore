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
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2-E5C: Predicate and Expression Index Experiments in PostgreSQL.
 *
 * Scenarios:
 * - IP1: Prefix LIKE before and after ordinary B-tree index (Redstone-%).
 * - IP2: Contains LIKE with ordinary B-tree index (%Redstone%).
 * - IP3: lower(display_name) = ? with ordinary index (Redstone-050000 & rEdStOnE-050000).
 * - IP4: lower(display_name) = ? with expression index idx_pt_lower_name.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.MethodName.class)
public class PostgresPredicateIndexExperimentsTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aethercore_predicate_test")
            .withUsername("test")
            .withPassword("test");

    private static final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static final String LIKE_QUERY_SQL = """
            SELECT id, display_name, payload
            FROM predicate_trade
            WHERE display_name LIKE ?
            """;

    private static final String LOWER_QUERY_SQL = """
            SELECT id, display_name, payload
            FROM predicate_trade
            WHERE lower(display_name) = ?
            """;

    private static final String EXACT_QUERY_SQL = """
            SELECT id, display_name, payload
            FROM predicate_trade
            WHERE display_name = ?
            """;

    private static String postgresServerVersion;
    private static String jdbcDriverVersion;
    private static String columnCollation;
    private static final Map<String, Object> runtimeSettings = new LinkedHashMap<>();
    private static final Map<String, Object> observations = new ConcurrentHashMap<>();

    public record PredicateTradeRow(long id, String displayName, String payload) {}

    @BeforeAll
    static void setUpAll() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", postgres.getUsername());
        props.setProperty("password", postgres.getPassword());
        props.setProperty("prepareThreshold", "0");

        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), props);
             Statement stmt = conn.createStatement()) {

            DatabaseMetaData meta = conn.getMetaData();
            postgresServerVersion = meta.getDatabaseProductVersion();
            jdbcDriverVersion = meta.getDriverVersion();

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

            // 1. Initialize probe table
            try (InputStream is = PostgresPredicateIndexExperimentsTest.class.getResourceAsStream("/sql/predicate-index-probe.sql")) {
                if (is == null) {
                    throw new IllegalStateException("Resource /sql/predicate-index-probe.sql not found on classpath");
                }
                String ddl = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                stmt.execute(ddl);
            }

            // 2. Seed 100,001 deterministic rows
            stmt.execute("TRUNCATE TABLE predicate_trade");
            String seedSql = """
                    INSERT INTO predicate_trade (id, display_name, payload)
                    SELECT g,
                           CASE
                               WHEN g % 1000 = 0
                                   THEN 'Redstone-' || lpad(g::text, 6, '0')
                               WHEN g % 1000 = 1
                                   THEN 'Guild-' || lpad(g::text, 6, '0') || '-Redstone'
                               ELSE 'Guild-' || lpad(g::text, 6, '0')
                           END,
                           repeat(md5(g::text), 4)
                    FROM generate_series(1, 100000) g;

                    INSERT INTO predicate_trade (id, display_name, payload)
                    VALUES (100001, 'rEdStOnE-050000', repeat(md5('100001'), 4));
                    """;
            stmt.execute(seedSql);
            stmt.execute("ANALYZE predicate_trade");

            // Verify row count
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM predicate_trade")) {
                assertTrue(rs.next());
                assertEquals(100001L, rs.getLong(1), "Seed count must be exactly 100,001 rows");
            }

            // Verify explicit column collation "C"
            String collationSql = """
                    SELECT c.collname
                    FROM pg_attribute a
                    JOIN pg_class t ON a.attrelid = t.oid
                    JOIN pg_collation c ON a.attcollation = c.oid
                    WHERE t.relname = 'predicate_trade' AND a.attname = 'display_name'
                    """;
            try (ResultSet rs = stmt.executeQuery(collationSql)) {
                assertTrue(rs.next(), "Collation for column display_name must be present");
                columnCollation = rs.getString(1);
                assertEquals("C", columnCollation, "Column display_name collation must be 'C'");
            }
            runtimeSettings.put("column_collation", columnCollation);
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

    // Independent Oracle helpers
    private static List<Long> oraclePrefixIds() {
        // Redstone-% matches g in 1..100000 where g % 1000 == 0 -> 100 IDs
        List<Long> ids = new ArrayList<>(100);
        for (long g = 1000L; g <= 100000L; g += 1000L) {
            ids.add(g);
        }
        return Collections.unmodifiableList(ids);
    }

    private static List<Long> oracleContainsIds() {
        // %Redstone% matches g % 1000 == 0 (100) union g % 1000 == 1 (100) -> 200 IDs
        List<Long> ids = new ArrayList<>(200);
        for (long g = 1L; g <= 100000L; g++) {
            if (g % 1000L == 0L || g % 1000L == 1L) {
                ids.add(g);
            }
        }
        return Collections.unmodifiableList(ids);
    }

    private static List<Long> oracleLowerEqualityIds() {
        // lower(display_name) = 'redstone-050000' matches g=50000 (Redstone-050000) and g=100001 (rEdStOnE-050000)
        return List.of(50000L, 100001L);
    }

    private static List<Long> oracleRawEqualityIds() {
        // display_name = 'redstone-050000' matches 0 rows in case-sensitive 'C' collation
        return List.of();
    }

    private static List<PredicateTradeRow> executeQuery(PreparedStatement ps) throws SQLException {
        List<PredicateTradeRow> rows = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new PredicateTradeRow(
                        rs.getLong("id"),
                        rs.getString("display_name"),
                        rs.getString("payload")
                ));
            }
        }
        return rows;
    }

    private static List<Long> extractSortedIds(List<PredicateTradeRow> rows) {
        List<Long> ids = new ArrayList<>(rows.size());
        for (PredicateTradeRow r : rows) {
            ids.add(r.id());
        }
        Collections.sort(ids);
        return ids;
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
    @DisplayName("IP1. Prefix LIKE Before and After Ordinary B-Tree Index (Redstone-%)")
    void ip1_prefixLikeBeforeAndAfterBTreeIndex() throws Exception {
        try (Connection conn = getLabConnection();
             Statement stmt = conn.createStatement()) {

            // 1. Setup inventory: Primary Key only (no secondary indexes)
            stmt.execute("DROP INDEX IF EXISTS idx_pt_name");
            stmt.execute("DROP INDEX IF EXISTS idx_pt_lower_name");
            stmt.execute("ANALYZE predicate_trade");

            List<Long> expectedIds = oraclePrefixIds();
            assertEquals(100, expectedIds.size());

            // Measurement 1: prefix_no_index
            List<PredicateTradeRow> noIndexRows;
            try (PreparedStatement ps = conn.prepareStatement(LIKE_QUERY_SQL)) {
                ps.setString(1, "Redstone-%");
                noIndexRows = executeQuery(ps);
            }
            assertEquals(100, noIndexRows.size(), "prefix_no_index must return 100 rows");
            List<Long> noIndexIds = extractSortedIds(noIndexRows);
            assertEquals(expectedIds, noIndexIds, "prefix_no_index IDs must match independent oracle");
            for (PredicateTradeRow r : noIndexRows) {
                assertTrue(r.displayName().startsWith("Redstone-"));
                assertNotNull(r.payload());
            }

            JsonNode rawExplainNoIndex;
            String explainSql = "EXPLAIN (ANALYZE, BUFFERS, SETTINGS, TIMING OFF, FORMAT JSON) " + LIKE_QUERY_SQL;
            try (PreparedStatement ps = conn.prepareStatement(explainSql)) {
                ps.setString(1, "Redstone-%");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    rawExplainNoIndex = objectMapper.readTree(rs.getString(1));
                }
            }
            ExplainPlanReader.PlanReport noIndexReport = ExplainPlanReader.parse(rawExplainNoIndex);

            // 2. Setup inventory: Add ordinary B-tree index idx_pt_name
            stmt.execute("CREATE INDEX idx_pt_name ON predicate_trade (display_name)");
            stmt.execute("ANALYZE predicate_trade");

            // Measurement 2: prefix_btree
            List<PredicateTradeRow> btreeRows;
            try (PreparedStatement ps = conn.prepareStatement(LIKE_QUERY_SQL)) {
                ps.setString(1, "Redstone-%");
                btreeRows = executeQuery(ps);
            }
            assertEquals(100, btreeRows.size(), "prefix_btree must return 100 rows");
            List<Long> btreeIds = extractSortedIds(btreeRows);
            assertEquals(expectedIds, btreeIds, "prefix_btree IDs must match independent oracle");
            assertEquals(noIndexIds, btreeIds, "Both plans must produce identical result IDs");

            JsonNode rawExplainBTree;
            try (PreparedStatement ps = conn.prepareStatement(explainSql)) {
                ps.setString(1, "Redstone-%");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    rawExplainBTree = objectMapper.readTree(rs.getString(1));
                }
            }
            ExplainPlanReader.PlanReport btreeReport = ExplainPlanReader.parse(rawExplainBTree);

            // Inspect plan nodes
            ExplainPlanReader.PlanNodeSummary noIndexScan = findTableScanNode(noIndexReport, "predicate_trade");
            ExplainPlanReader.PlanNodeSummary btreeScan = findTableScanNode(btreeReport, "predicate_trade");

            boolean noIndexIsSeqScan = noIndexScan != null && "Seq Scan".equalsIgnoreCase(noIndexScan.nodeType());
            boolean btreeUsesIndex = btreeScan != null && (
                    "Index Scan".equalsIgnoreCase(btreeScan.nodeType()) ||
                    "Bitmap Heap Scan".equalsIgnoreCase(btreeScan.nodeType())
            ) && "idx_pt_name".equalsIgnoreCase(btreeScan.indexName());

            boolean hypothesisMatched = noIndexIsSeqScan && btreeUsesIndex;

            Map<String, Object> measurements = new LinkedHashMap<>();
            measurements.put("prefix_no_index", Map.of(
                    "sql", LIKE_QUERY_SQL.trim(),
                    "params", Map.of("pattern", "Redstone-%"),
                    "returnedIds", noIndexIds,
                    "rawExplainAnalyzeJson", rawExplainNoIndex,
                    "report", noIndexReport
            ));
            measurements.put("prefix_btree", Map.of(
                    "sql", LIKE_QUERY_SQL.trim(),
                    "params", Map.of("pattern", "Redstone-%"),
                    "returnedIds", btreeIds,
                    "rawExplainAnalyzeJson", rawExplainBTree,
                    "report", btreeReport
            ));

            Map<String, Object> ip1Obs = new LinkedHashMap<>();
            ip1Obs.put("caseId", "IP1");
            ip1Obs.put("description", "Prefix LIKE before and after ordinary B-Tree index (Redstone-%)");
            ip1Obs.put("collation", columnCollation);
            ip1Obs.put("expectedIds", expectedIds);
            ip1Obs.put("returnedIds", Map.of(
                    "prefixNoIndex", noIndexIds,
                    "prefixBTree", btreeIds
            ));
            ip1Obs.put("measurements", measurements);
            ip1Obs.put("hypothesis", "Without index, engine performs Seq Scan; with B-tree on C collation, engine performs index/bitmap range seek");
            ip1Obs.put("hypothesisStatus", hypothesisMatched ? "MATCHED" : "DIFFERENT");
            ip1Obs.put("interpretation", hypothesisMatched
                    ? "Ordinary B-tree index on C collation enabled direct prefix range matching on idx_pt_name, replacing full table Seq Scan."
                    : String.format("Observed noIndexScan=%s, btreeScan=%s",
                            noIndexScan != null ? noIndexScan.nodeType() : "NONE",
                            btreeScan != null ? btreeScan.nodeType() : "NONE"));
            ip1Obs.put("assertionsPassed", true);

            observations.put("IP1", ip1Obs);
        }
    }

    @Test
    @DisplayName("IP2. Contains LIKE with Ordinary B-Tree Index (%Redstone%)")
    void ip2_containsLikeWithOrdinaryBTree() throws Exception {
        try (Connection conn = getLabConnection();
             Statement stmt = conn.createStatement()) {

            // Inventory: only idx_pt_name
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pt_name ON predicate_trade (display_name)");
            stmt.execute("DROP INDEX IF EXISTS idx_pt_lower_name");
            stmt.execute("ANALYZE predicate_trade");

            List<Long> expectedIds = oracleContainsIds();
            assertEquals(200, expectedIds.size());

            // Measurement 3: contains_like
            List<PredicateTradeRow> rows;
            try (PreparedStatement ps = conn.prepareStatement(LIKE_QUERY_SQL)) {
                ps.setString(1, "%Redstone%");
                rows = executeQuery(ps);
            }
            assertEquals(200, rows.size(), "contains_like must return 200 rows");
            List<Long> returnedIds = extractSortedIds(rows);
            assertEquals(expectedIds, returnedIds, "contains_like IDs must match independent oracle");
            for (PredicateTradeRow r : rows) {
                assertTrue(r.displayName().contains("Redstone"));
                assertNotNull(r.payload());
            }

            JsonNode rawExplain;
            String explainSql = "EXPLAIN (ANALYZE, BUFFERS, SETTINGS, TIMING OFF, FORMAT JSON) " + LIKE_QUERY_SQL;
            try (PreparedStatement ps = conn.prepareStatement(explainSql)) {
                ps.setString(1, "%Redstone%");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    rawExplain = objectMapper.readTree(rs.getString(1));
                }
            }
            ExplainPlanReader.PlanReport report = ExplainPlanReader.parse(rawExplain);

            ExplainPlanReader.PlanNodeSummary scanNode = findTableScanNode(report, "predicate_trade");
            boolean isSeqScanWithFilter = scanNode != null && "Seq Scan".equalsIgnoreCase(scanNode.nodeType())
                    && scanNode.filter() != null && scanNode.filter().contains("~~");

            boolean hypothesisMatched = isSeqScanWithFilter;

            Map<String, Object> measurements = new LinkedHashMap<>();
            measurements.put("contains_like", Map.of(
                    "sql", LIKE_QUERY_SQL.trim(),
                    "params", Map.of("pattern", "%Redstone%"),
                    "returnedIds", returnedIds,
                    "rawExplainAnalyzeJson", rawExplain,
                    "report", report
            ));

            Map<String, Object> ip2Obs = new LinkedHashMap<>();
            ip2Obs.put("caseId", "IP2");
            ip2Obs.put("description", "Contains LIKE with ordinary B-Tree index (%Redstone%)");
            ip2Obs.put("collation", columnCollation);
            ip2Obs.put("expectedIds", expectedIds);
            ip2Obs.put("returnedIds", returnedIds);
            ip2Obs.put("measurements", measurements);
            ip2Obs.put("hypothesis", "Ordinary B-tree cannot narrow leading wildcard pattern, forcing Seq Scan + Filter");
            ip2Obs.put("hypothesisStatus", hypothesisMatched ? "MATCHED" : "DIFFERENT");
            ip2Obs.put("interpretation", hypothesisMatched
                    ? "Ordinary B-Tree index on display_name cannot form a prefix seek key for leading wildcard '%Redstone%', falling back to Seq Scan with filter."
                    : String.format("Observed scanNode=%s (filter=%s)", scanNode != null ? scanNode.nodeType() : "NONE", scanNode != null ? scanNode.filter() : "NONE"));
            ip2Obs.put("assertionsPassed", true);

            observations.put("IP2", ip2Obs);
        }
    }

    @Test
    @DisplayName("IP3. lower(display_name) = ? with Ordinary Index")
    void ip3_lowerFunctionWithOrdinaryIndex() throws Exception {
        try (Connection conn = getLabConnection();
             Statement stmt = conn.createStatement()) {

            // Inventory: only idx_pt_name
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pt_name ON predicate_trade (display_name)");
            stmt.execute("DROP INDEX IF EXISTS idx_pt_lower_name");
            stmt.execute("ANALYZE predicate_trade");

            List<Long> expectedIds = oracleLowerEqualityIds();
            assertEquals(2, expectedIds.size());

            // Measurement 4: lower_ordinary_index
            List<PredicateTradeRow> lowerRows;
            try (PreparedStatement ps = conn.prepareStatement(LOWER_QUERY_SQL)) {
                ps.setString(1, "redstone-050000");
                lowerRows = executeQuery(ps);
            }
            assertEquals(2, lowerRows.size(), "lower query must return 2 rows (50000 and 100001)");
            List<Long> lowerReturnedIds = extractSortedIds(lowerRows);
            assertEquals(expectedIds, lowerReturnedIds, "lower query IDs must match independent oracle");

            // Also verify raw equality returns 0 rows
            List<PredicateTradeRow> rawEqRows;
            try (PreparedStatement ps = conn.prepareStatement(EXACT_QUERY_SQL)) {
                ps.setString(1, "redstone-050000");
                rawEqRows = executeQuery(ps);
            }
            assertEquals(0, rawEqRows.size(), "Raw equality display_name = 'redstone-050000' must return 0 rows");
            List<Long> rawEqIds = extractSortedIds(rawEqRows);

            JsonNode rawExplain;
            String explainSql = "EXPLAIN (ANALYZE, BUFFERS, SETTINGS, TIMING OFF, FORMAT JSON) " + LOWER_QUERY_SQL;
            try (PreparedStatement ps = conn.prepareStatement(explainSql)) {
                ps.setString(1, "redstone-050000");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    rawExplain = objectMapper.readTree(rs.getString(1));
                }
            }
            ExplainPlanReader.PlanReport report = ExplainPlanReader.parse(rawExplain);

            ExplainPlanReader.PlanNodeSummary scanNode = findTableScanNode(report, "predicate_trade");
            boolean isSeqScanWithFilter = scanNode != null && "Seq Scan".equalsIgnoreCase(scanNode.nodeType())
                    && scanNode.filter() != null && scanNode.filter().toLowerCase().contains("lower");

            boolean hypothesisMatched = isSeqScanWithFilter;

            Map<String, Object> measurements = new LinkedHashMap<>();
            measurements.put("lower_ordinary_index", Map.of(
                    "sql", LOWER_QUERY_SQL.trim(),
                    "params", Map.of("value", "redstone-050000"),
                    "returnedIds", lowerReturnedIds,
                    "rawExplainAnalyzeJson", rawExplain,
                    "report", report
            ));

            Map<String, Object> ip3Obs = new LinkedHashMap<>();
            ip3Obs.put("caseId", "IP3");
            ip3Obs.put("description", "lower(display_name) = ? with ordinary index idx_pt_name");
            ip3Obs.put("collation", columnCollation);
            ip3Obs.put("expectedIds", expectedIds);
            ip3Obs.put("returnedIds", lowerReturnedIds);
            ip3Obs.put("rawEqualityReturnedIds", rawEqIds);
            ip3Obs.put("measurements", measurements);
            ip3Obs.put("hypothesis", "Ordinary index on column does not match function expression lower(column), forcing Seq Scan");
            ip3Obs.put("hypothesisStatus", hypothesisMatched ? "MATCHED" : "DIFFERENT");
            ip3Obs.put("interpretation", hypothesisMatched
                    ? "Wrapping column in lower() prevents optimizer from utilizing ordinary B-tree on display_name directly, forcing Seq Scan."
                    : String.format("Observed scanNode=%s (filter=%s)", scanNode != null ? scanNode.nodeType() : "NONE", scanNode != null ? scanNode.filter() : "NONE"));
            ip3Obs.put("assertionsPassed", true);

            observations.put("IP3", ip3Obs);
        }
    }

    @Test
    @DisplayName("IP4. lower(display_name) = ? with Expression Index (idx_pt_lower_name)")
    void ip4_lowerFunctionWithExpressionIndex() throws Exception {
        try (Connection conn = getLabConnection();
             Statement stmt = conn.createStatement()) {

            // Inventory: idx_pt_name + idx_pt_lower_name
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pt_name ON predicate_trade (display_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pt_lower_name ON predicate_trade (lower(display_name))");
            stmt.execute("ANALYZE predicate_trade");

            List<Long> expectedIds = oracleLowerEqualityIds();
            assertEquals(2, expectedIds.size());

            // Measurement 5: lower_expression_index
            List<PredicateTradeRow> rows;
            try (PreparedStatement ps = conn.prepareStatement(LOWER_QUERY_SQL)) {
                ps.setString(1, "redstone-050000");
                rows = executeQuery(ps);
            }
            assertEquals(2, rows.size(), "Expression index query must return 2 rows");
            List<Long> returnedIds = extractSortedIds(rows);
            assertEquals(expectedIds, returnedIds, "Expression index query IDs must match independent oracle");

            JsonNode rawExplain;
            String explainSql = "EXPLAIN (ANALYZE, BUFFERS, SETTINGS, TIMING OFF, FORMAT JSON) " + LOWER_QUERY_SQL;
            try (PreparedStatement ps = conn.prepareStatement(explainSql)) {
                ps.setString(1, "redstone-050000");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    rawExplain = objectMapper.readTree(rs.getString(1));
                }
            }
            ExplainPlanReader.PlanReport report = ExplainPlanReader.parse(rawExplain);

            ExplainPlanReader.PlanNodeSummary scanNode = findTableScanNode(report, "predicate_trade");
            boolean usesExpressionIndex = scanNode != null && (
                    "Index Scan".equalsIgnoreCase(scanNode.nodeType()) ||
                    "Bitmap Heap Scan".equalsIgnoreCase(scanNode.nodeType())
            ) && "idx_pt_lower_name".equalsIgnoreCase(scanNode.indexName());

            boolean hypothesisMatched = usesExpressionIndex;

            Map<String, Object> measurements = new LinkedHashMap<>();
            measurements.put("lower_expression_index", Map.of(
                    "sql", LOWER_QUERY_SQL.trim(),
                    "params", Map.of("value", "redstone-050000"),
                    "returnedIds", returnedIds,
                    "rawExplainAnalyzeJson", rawExplain,
                    "report", report
            ));

            Map<String, Object> ip4Obs = new LinkedHashMap<>();
            ip4Obs.put("caseId", "IP4");
            ip4Obs.put("description", "lower(display_name) = ? with matching expression index idx_pt_lower_name");
            ip4Obs.put("collation", columnCollation);
            ip4Obs.put("expectedIds", expectedIds);
            ip4Obs.put("returnedIds", returnedIds);
            ip4Obs.put("measurements", measurements);
            ip4Obs.put("hypothesis", "Matching expression index idx_pt_lower_name enables direct index seek for lower(column) predicate");
            ip4Obs.put("hypothesisStatus", hypothesisMatched ? "MATCHED" : "DIFFERENT");
            ip4Obs.put("interpretation", hypothesisMatched
                    ? "Expression index idx_pt_lower_name on lower(display_name) successfully matched the predicate, replacing Seq Scan with index seek."
                    : String.format("Observed scanNode=%s (indexName=%s)", scanNode != null ? scanNode.nodeType() : "NONE", scanNode != null ? scanNode.indexName() : "NONE"));
            ip4Obs.put("assertionsPassed", true);

            observations.put("IP4", ip4Obs);
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

        File file = new File(dir, "predicate-index-observations.json");
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
            throw new RuntimeException("Failed to export predicate index observations to " + file.getAbsolutePath(), e);
        }
    }
}
