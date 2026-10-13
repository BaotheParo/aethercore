package com.aethercore.backend.pagination;

import com.aethercore.backend.fetch.FetchBuyer;
import com.aethercore.backend.fetch.FetchOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.Persistence;
import jakarta.persistence.TypedQuery;
import org.hibernate.HibernateException;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * M2-E4B: Collection Fetch, Pagination, and Two-Step Query Experiments.
 * Bootstraps Java SE JPA (RESOURCE_LOCAL, Hibernate 6.5) against PostgreSQL 16 Testcontainers.
 *
 * Verifies:
 * - PG1: Collection fetch with pagination under lenient policy triggers in-memory pagination (excessive entity loads).
 * - PG2: Strict policy (fail_on_pagination_over_collection_fetch=true) fails fast and rejects query execution.
 * - PG3: Two-step pagination for page 0 (offset 0, size 2) loads exact entities with 2 statements.
 * - PG4: Two-step pagination for page 1 (offset 2, size 2) yields non-overlapping next page.
 * - PG5: Two-step pagination for out-of-bounds page (offset 99) short-circuits with 1 statement and 0 entity loads.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.MethodName.class)
public class JpaCollectionPaginationExperimentsTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aethercore_pagination_test")
            .withUsername("test")
            .withPassword("test");

    private static EntityManagerFactory unsafeEmf;
    private static EntityManagerFactory strictEmf;
    private static final PaginationStatementInspector unsafeInspector = new PaginationStatementInspector();
    private static final PaginationStatementInspector strictInspector = new PaginationStatementInspector();

    private static String postgresServerVersion;
    private static final Map<String, PaginationCaseObservation> observations = new ConcurrentHashMap<>();

    @BeforeAll
    static void setUpAll() throws Exception {
        // 1. Initialize schema and query runtime DB version
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery("SHOW server_version")) {
                if (rs.next()) {
                    postgresServerVersion = rs.getString(1);
                }
            }

            try (InputStream is = JpaCollectionPaginationExperimentsTest.class.getResourceAsStream("/sql/fetch-probe.sql")) {
                if (is == null) {
                    throw new IllegalStateException("Resource /sql/fetch-probe.sql not found on classpath");
                }
                String ddl = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                stmt.execute(ddl);
            }
        }

        // 2. Base configuration properties
        Map<String, Object> baseProps = new HashMap<>();
        baseProps.put("jakarta.persistence.jdbc.url", postgres.getJdbcUrl());
        baseProps.put("jakarta.persistence.jdbc.user", postgres.getUsername());
        baseProps.put("jakarta.persistence.jdbc.password", postgres.getPassword());
        baseProps.put("jakarta.persistence.jdbc.driver", "org.postgresql.Driver");
        baseProps.put("hibernate.connection.provider_disables_autocommit", "true");
        baseProps.put("hibernate.generate_statistics", "true");
        baseProps.put("hibernate.cache.use_second_level_cache", "false");
        baseProps.put("hibernate.cache.use_query_cache", "false");
        baseProps.put("hibernate.default_batch_fetch_size", "0");

        // Bootstrap unsafe EMF (fail_on_pagination_over_collection_fetch = false)
        Map<String, Object> unsafeProps = new HashMap<>(baseProps);
        unsafeProps.put("hibernate.query.fail_on_pagination_over_collection_fetch", "false");
        unsafeProps.put("hibernate.session_factory.statement_inspector", unsafeInspector);
        unsafeProps.put("hibernate.statement_inspector", unsafeInspector);
        unsafeEmf = Persistence.createEntityManagerFactory("aethercore-pagination-jpa-test", unsafeProps);

        // Bootstrap strict EMF (fail_on_pagination_over_collection_fetch = true)
        Map<String, Object> strictProps = new HashMap<>(baseProps);
        strictProps.put("hibernate.query.fail_on_pagination_over_collection_fetch", "true");
        strictProps.put("hibernate.session_factory.statement_inspector", strictInspector);
        strictProps.put("hibernate.statement_inspector", strictInspector);
        strictEmf = Persistence.createEntityManagerFactory("aethercore-pagination-jpa-test", strictProps);
    }

    @AfterAll
    static void tearDownAll() {
        if (unsafeEmf != null && unsafeEmf.isOpen()) {
            unsafeEmf.close();
        }
        if (strictEmf != null && strictEmf.isOpen()) {
            strictEmf.close();
        }
    }

    private static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /**
     * Seeds the standard 5-buyer dataset:
     * All 5 buyers have the identical name 'Trader' to enforce order tiebreaker by id.
     * Buyer 1: orders 101, 102, 103 (qty 1)
     * Buyer 2: no orders
     * Buyer 3: orders 301, 302 (qty 1)
     * Buyer 4: order 401 (qty 1)
     * Buyer 5: orders 501, 502, 503, 504 (qty 1)
     */
    private void seedDataset() throws SQLException {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM fetch_order");
            stmt.executeUpdate("DELETE FROM fetch_buyer");

            for (int i = 1; i <= 5; i++) {
                stmt.executeUpdate("INSERT INTO fetch_buyer (id, name) VALUES (" + i + ", 'Trader')");
            }

            // Buyer 1: 3 orders
            stmt.executeUpdate("INSERT INTO fetch_order (id, buyer_id, quantity) VALUES (101, 1, 1)");
            stmt.executeUpdate("INSERT INTO fetch_order (id, buyer_id, quantity) VALUES (102, 1, 1)");
            stmt.executeUpdate("INSERT INTO fetch_order (id, buyer_id, quantity) VALUES (103, 1, 1)");

            // Buyer 2: 0 orders

            // Buyer 3: 2 orders
            stmt.executeUpdate("INSERT INTO fetch_order (id, buyer_id, quantity) VALUES (301, 3, 1)");
            stmt.executeUpdate("INSERT INTO fetch_order (id, buyer_id, quantity) VALUES (302, 3, 1)");

            // Buyer 4: 1 order
            stmt.executeUpdate("INSERT INTO fetch_order (id, buyer_id, quantity) VALUES (401, 4, 1)");

            // Buyer 5: 4 orders
            stmt.executeUpdate("INSERT INTO fetch_order (id, buyer_id, quantity) VALUES (501, 5, 1)");
            stmt.executeUpdate("INSERT INTO fetch_order (id, buyer_id, quantity) VALUES (502, 5, 1)");
            stmt.executeUpdate("INSERT INTO fetch_order (id, buyer_id, quantity) VALUES (503, 5, 1)");
            stmt.executeUpdate("INSERT INTO fetch_order (id, buyer_id, quantity) VALUES (504, 5, 1)");
        }
    }

    private void clearTelemetry(EntityManagerFactory emf, PaginationStatementInspector inspector) {
        inspector.clear();
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
    }

    private long getStatementCount(EntityManagerFactory emf) {
        return emf.unwrap(SessionFactory.class).getStatistics().getPrepareStatementCount();
    }

    private long getBuyerLoadCount(EntityManagerFactory emf) {
        return emf.unwrap(SessionFactory.class).getStatistics()
                .getEntityStatistics(FetchBuyer.class.getName()).getLoadCount();
    }

    private long getOrderLoadCount(EntityManagerFactory emf) {
        return emf.unwrap(SessionFactory.class).getStatistics()
                .getEntityStatistics(FetchOrder.class.getName()).getLoadCount();
    }

    @Test
    @DisplayName("PG1. Unsafe Policy: Collection fetch with pagination executes 1 SQL but loads all entities into memory")
    void pg1_unsafeInMemoryCollectionFetchPagination() throws SQLException {
        seedDataset();
        clearTelemetry(unsafeEmf, unsafeInspector);

        EntityManager em = unsafeEmf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();

            TypedQuery<FetchBuyer> query = em.createQuery(
                    "select b from FetchBuyer b left join fetch b.orders order by b.name asc, b.id asc",
                    FetchBuyer.class
            );
            query.setFirstResult(0);
            query.setMaxResults(2);

            List<FetchBuyer> buyers = query.getResultList();

            long stmtCount = getStatementCount(unsafeEmf);
            long buyerLoads = getBuyerLoadCount(unsafeEmf);
            long orderLoads = getOrderLoadCount(unsafeEmf);
            List<String> capturedSql = unsafeInspector.getStatements();

            tx.commit();

            // Verification of returned page contract
            assertEquals(2, buyers.size(), "Returned page size must equal requested maxResults (2)");
            assertEquals(1L, buyers.get(0).getId());
            assertEquals(2L, buyers.get(1).getId());

            // Buyer 1 must have 3 orders, Buyer 2 has 0 orders
            assertEquals(3, buyers.get(0).getOrders().size());
            assertEquals(0, buyers.get(1).getOrders().size());

            // Build DTO views
            List<BuyerOrdersView> views = new ArrayList<>();
            for (FetchBuyer b : buyers) {
                List<BuyerOrdersView.OrderLine> orderLines = new ArrayList<>();
                for (FetchOrder o : b.getOrders()) {
                    orderLines.add(new BuyerOrdersView.OrderLine(o.getId(), o.getQuantity()));
                }
                orderLines.sort(Comparator.comparing(BuyerOrdersView.OrderLine::orderId));
                views.add(new BuyerOrdersView(b.getId(), b.getName(), orderLines));
            }

            // Key Invariants for PG1:
            // 1. Exactly 1 statement prepared (no secondary queries)
            assertEquals(1, stmtCount, "Prepared statement count must be 1");
            assertEquals(1, capturedSql.size(), "Captured SQL count must be 1");

            // 2. The single SQL statement does NOT contain LIMIT or OFFSET clauses
            String executedSql = capturedSql.get(0).toLowerCase();
            assertFalse(executedSql.contains("limit") || executedSql.contains("offset") || executedSql.contains("fetch first"),
                    "SQL must not contain database limit/offset clause because Hibernate applies pagination in memory");

            // 3. Excessive entity materialization: all 5 buyers and all 10 orders were loaded into memory!
            assertEquals(5, buyerLoads, "In-memory pagination loads all 5 buyers from the table");
            assertEquals(10, orderLoads, "In-memory pagination loads all 10 orders from the table");
            assertTrue(buyerLoads > 2, "Proves excessive entity loading: loaded buyers > page size");
            assertTrue(orderLoads > 3, "Proves excessive entity loading: loaded orders > page orders");

            observations.put("PG1", new PaginationCaseObservation(
                    "PG1", "Collection fetch with in-memory pagination (lenient)",
                    0, 2, false, "RETURNED_PAGE",
                    null, List.of(1L, 2L), views,
                    (int) stmtCount, (int) buyerLoads, (int) orderLoads,
                    capturedSql, null, null, true
            ));
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }
    }

    @Test
    @DisplayName("PG2. Strict Policy: Fail-fast guard rejects pagination over collection fetch")
    void pg2_strictFailFastRejection() throws SQLException {
        seedDataset();
        clearTelemetry(strictEmf, strictInspector);

        EntityManager em = strictEmf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        String thrownClass = null;
        String thrownMessage = null;
        boolean rejected = false;

        try {
            tx.begin();

            TypedQuery<FetchBuyer> query = em.createQuery(
                    "select b from FetchBuyer b left join fetch b.orders order by b.name asc, b.id asc",
                    FetchBuyer.class
            );
            query.setFirstResult(0);
            query.setMaxResults(2);

            try {
                query.getResultList();
                fail("Query must be rejected by fail_on_pagination_over_collection_fetch=true");
            } catch (Exception ex) {
                rejected = true;
                thrownClass = ex.getClass().getName();
                thrownMessage = ex.getMessage();

                // Find root cause
                Throwable cause = ex;
                boolean foundHibernateRejection = false;
                while (cause != null) {
                    if (cause instanceof HibernateException ||
                        (cause.getMessage() != null && cause.getMessage().toLowerCase().contains("collection fetch"))) {
                        foundHibernateRejection = true;
                        break;
                    }
                    cause = cause.getCause();
                }
                assertTrue(foundHibernateRejection, "Exception cause chain must indicate collection fetch rejection");
            }

            long stmtCount = getStatementCount(strictEmf);
            long buyerLoads = getBuyerLoadCount(strictEmf);
            long orderLoads = getOrderLoadCount(strictEmf);
            List<String> capturedSql = strictInspector.getStatements();

            tx.rollback();

            assertTrue(rejected, "Strict policy must fail fast on pagination over collection fetch");
            assertEquals(0, stmtCount, "No SQL prepared when fail-fast guard triggers");
            assertEquals(0, capturedSql.size(), "SQL statements list must be empty");
            assertEquals(0, buyerLoads, "0 buyers loaded");
            assertEquals(0, orderLoads, "0 orders loaded");

            observations.put("PG2", new PaginationCaseObservation(
                    "PG2", "Strict fail-fast guard rejection",
                    0, 2, true, "EXPECTED_REJECTION",
                    null, Collections.emptyList(), Collections.emptyList(),
                    (int) stmtCount, (int) buyerLoads, (int) orderLoads,
                    capturedSql, thrownClass, thrownMessage, true
            ));
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }
    }

    @Test
    @DisplayName("PG3. Two-Step Pagination: Page 0 (offset 0, size 2) via strict policy loads exactly page entities")
    void pg3_twoStepReadPageZero() throws SQLException {
        seedDataset();
        clearTelemetry(strictEmf, strictInspector);

        EntityManager em = strictEmf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();

            BuyerPageReader.TwoStepResult result = BuyerPageReader.readPage(em, 0, 2);

            long stmtCount = getStatementCount(strictEmf);
            long buyerLoads = getBuyerLoadCount(strictEmf);
            long orderLoads = getOrderLoadCount(strictEmf);
            List<String> capturedSql = strictInspector.getStatements();

            tx.commit();

            // Verification of Phase 1 IDs and views
            assertEquals(List.of(1L, 2L), result.phase1Ids(), "Phase 1 IDs must be [1, 2]");
            assertEquals(2, result.views().size(), "Result views size must be 2");

            BuyerOrdersView v1 = result.views().get(0);
            assertEquals(1L, v1.buyerId());
            assertEquals("Trader", v1.buyerName());
            assertEquals(3, v1.orders().size());
            assertEquals(101L, v1.orders().get(0).orderId());
            assertEquals(102L, v1.orders().get(1).orderId());
            assertEquals(103L, v1.orders().get(2).orderId());

            BuyerOrdersView v2 = result.views().get(1);
            assertEquals(2L, v2.buyerId());
            assertEquals("Trader", v2.buyerName());
            assertEquals(0, v2.orders().size(), "Buyer 2 with empty orders must be preserved in page");

            // Key Invariants for PG3:
            // 1. Exactly 2 prepared statements: Step 1 (IDs) + Step 2 (Collection fetch)
            assertEquals(2, stmtCount, "Two-step query must execute exactly 2 statements");
            assertEquals(2, capturedSql.size(), "Captured SQL count must be 2");

            // 2. Step 1 SQL contains database-level pagination
            String sql1 = capturedSql.get(0).toLowerCase();
            assertTrue(sql1.contains("limit") || sql1.contains("offset") || sql1.contains("fetch first"),
                    "Step 1 query must include database limit/offset clause");

            // 3. Step 2 SQL contains IN predicate without pagination limit
            String sql2 = capturedSql.get(1).toLowerCase();
            assertTrue(sql2.contains("in"), "Step 2 query must contain IN predicate");
            assertFalse(sql2.contains("limit") || sql2.contains("offset") || sql2.contains("fetch first"),
                    "Step 2 query must not paginate, as it fetches whole collections for selected IDs");

            // 4. Exact entity loading: only 2 buyers and 3 orders
            assertEquals(2, buyerLoads, "Must load only 2 buyers for page 0");
            assertEquals(3, orderLoads, "Must load only 3 orders belonging to page 0 buyers");

            observations.put("PG3", new PaginationCaseObservation(
                    "PG3", "Two-step pagination (page 0)",
                    0, 2, true, "RETURNED_PAGE",
                    result.phase1Ids(), List.of(1L, 2L), result.views(),
                    (int) stmtCount, (int) buyerLoads, (int) orderLoads,
                    capturedSql, null, null, true
            ));
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }
    }

    @Test
    @DisplayName("PG4. Two-Step Pagination: Page 1 (offset 2, size 2) yields non-overlapping page [3, 4]")
    void pg4_twoStepReadPageOne() throws SQLException {
        seedDataset();
        clearTelemetry(strictEmf, strictInspector);

        EntityManager em = strictEmf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();

            BuyerPageReader.TwoStepResult result = BuyerPageReader.readPage(em, 2, 2);

            long stmtCount = getStatementCount(strictEmf);
            long buyerLoads = getBuyerLoadCount(strictEmf);
            long orderLoads = getOrderLoadCount(strictEmf);
            List<String> capturedSql = strictInspector.getStatements();

            tx.commit();

            // Verification of Phase 1 IDs and views
            assertEquals(List.of(3L, 4L), result.phase1Ids(), "Phase 1 IDs must be [3, 4]");
            assertEquals(2, result.views().size(), "Result views size must be 2");

            BuyerOrdersView v3 = result.views().get(0);
            assertEquals(3L, v3.buyerId());
            assertEquals(2, v3.orders().size());
            assertEquals(301L, v3.orders().get(0).orderId());
            assertEquals(302L, v3.orders().get(1).orderId());

            BuyerOrdersView v4 = result.views().get(1);
            assertEquals(4L, v4.buyerId());
            assertEquals(1, v4.orders().size());
            assertEquals(401L, v4.orders().get(0).orderId());

            // Non-overlapping with Page 0
            List<Long> page0ExpectedIds = List.of(1L, 2L);
            for (Long id : result.phase1Ids()) {
                assertFalse(page0ExpectedIds.contains(id), "Page 1 IDs must not overlap with Page 0");
            }

            // Key Invariants for PG4:
            assertEquals(2, stmtCount, "Must execute exactly 2 statements");
            assertEquals(2, buyerLoads, "Must load only 2 buyers for page 1");
            assertEquals(3, orderLoads, "Must load only 3 orders for page 1 buyers (2 for buyer 3 + 1 for buyer 4)");

            observations.put("PG4", new PaginationCaseObservation(
                    "PG4", "Two-step pagination (page 1)",
                    2, 2, true, "RETURNED_PAGE",
                    result.phase1Ids(), List.of(3L, 4L), result.views(),
                    (int) stmtCount, (int) buyerLoads, (int) orderLoads,
                    capturedSql, null, null, true
            ));
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }
    }

    @Test
    @DisplayName("PG5. Two-Step Pagination: Out-of-bounds page (offset 99) short-circuits with 1 statement and 0 entity loads")
    void pg5_twoStepReadEmptyPage() throws SQLException {
        seedDataset();
        clearTelemetry(strictEmf, strictInspector);

        EntityManager em = strictEmf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();

            BuyerPageReader.TwoStepResult result = BuyerPageReader.readPage(em, 99, 2);

            long stmtCount = getStatementCount(strictEmf);
            long buyerLoads = getBuyerLoadCount(strictEmf);
            long orderLoads = getOrderLoadCount(strictEmf);
            List<String> capturedSql = strictInspector.getStatements();

            tx.commit();

            assertTrue(result.phase1Ids().isEmpty(), "Phase 1 IDs must be empty");
            assertTrue(result.views().isEmpty(), "Result views must be empty");

            // Key Invariants for PG5:
            // 1. Only Step 1 was executed (short-circuit on empty IDs)
            assertEquals(1, stmtCount, "Must execute exactly 1 statement (Step 1 ID query)");
            assertEquals(1, capturedSql.size(), "Captured SQL count must be 1");

            // 2. 0 entities loaded
            assertEquals(0, buyerLoads, "0 buyers loaded on empty page");
            assertEquals(0, orderLoads, "0 orders loaded on empty page");

            observations.put("PG5", new PaginationCaseObservation(
                    "PG5", "Two-step pagination (empty page short-circuit)",
                    99, 2, true, "RETURNED_PAGE",
                    result.phase1Ids(), Collections.emptyList(), result.views(),
                    (int) stmtCount, (int) buyerLoads, (int) orderLoads,
                    capturedSql, null, null, true
            ));
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }
    }

    @AfterAll
    static void exportObservations() {
        if (observations.isEmpty()) return;
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

        File file = new File(dir, "pagination-observations.json");
        String runId = System.getProperty("lab.runId", "ad-hoc-" + Instant.now().toEpochMilli());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1.0.0");
        payload.put("runId", runId);
        String pgVer = "PostgreSQL " + (postgresServerVersion != null ? postgresServerVersion : "16-alpine");
        String hbVer = "Hibernate " + org.hibernate.Version.getVersionString();
        payload.put("environment", "Java SE JPA / " + hbVer + " / " + pgVer);
        payload.put("timestamp", Instant.now().toString());

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("unsafeEmf", Map.of(
                "failOnPaginationOverCollectionFetch", false,
                "cacheEnabled", false,
                "batchFetchSize", 0
        ));
        settings.put("strictEmf", Map.of(
                "failOnPaginationOverCollectionFetch", true,
                "cacheEnabled", false,
                "batchFetchSize", 0
        ));
        payload.put("settings", settings);

        List<String> keys = new ArrayList<>(observations.keySet());
        Collections.sort(keys);
        List<PaginationCaseObservation> scenarios = new ArrayList<>();
        for (String k : keys) {
            scenarios.add(observations.get(k));
        }
        payload.put("scenarios", scenarios);

        try {
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(file, payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to export pagination observations to " + file.getAbsolutePath(), e);
        }
    }

    public record PaginationCaseObservation(
            String caseId,
            String strategy,
            int offset,
            int pageSize,
            boolean policyEnabled,
            String outcome,
            List<Long> selectedIdsPhase1,
            List<Long> returnedBuyerIds,
            List<BuyerOrdersView> resultRows,
            int preparedStatementCount,
            int buyerEntityLoadCount,
            int orderEntityLoadCount,
            List<String> sqlStatements,
            String exceptionClass,
            String exceptionMessage,
            boolean assertionsPassed
    ) {}
}
