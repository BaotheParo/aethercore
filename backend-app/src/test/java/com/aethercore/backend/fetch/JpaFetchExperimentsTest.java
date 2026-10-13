package com.aethercore.backend.fetch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.Persistence;
import jakarta.persistence.PersistenceUnitUtil;
import jakarta.persistence.TypedQuery;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2-E4A: JPA Fetching, N+1, JOIN FETCH, EntityGraph, and DTO Projection experiments.
 * Bootstraps Java SE JPA (RESOURCE_LOCAL, Hibernate 6.5) against a PostgreSQL 16 Testcontainers instance.
 *
 * Verifies 5 key scenarios:
 * - NQ1: Baseline N+1 on D1 (1 root query + 6 secondary queries = 7 statements)
 * - NQ2: JOIN FETCH on D1 (1 statement)
 * - NQ3: Dynamic EntityGraph on D1 (1 statement)
 * - NQ4: JPQL constructor projection on D1 (1 statement, decoupled from persistence context)
 * - NQ5: Baseline N+1 with repeated buyers on D2 (1 root + 3 secondary = 4 statements via 1st-level cache)
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.MethodName.class)
public class JpaFetchExperimentsTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aethercore_fetch_test")
            .withUsername("test")
            .withPassword("test");

    private static EntityManagerFactory emf;
    private static String postgresServerVersion;
    private static final Map<String, FetchCaseObservation> observations = new ConcurrentHashMap<>();

    @BeforeAll
    static void setUpAll() throws Exception {
        // 1. Query runtime PostgreSQL version and initialize probe schema
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery("SHOW server_version")) {
                if (rs.next()) {
                    postgresServerVersion = rs.getString(1);
                }
            }

            try (InputStream is = JpaFetchExperimentsTest.class.getResourceAsStream("/sql/fetch-probe.sql")) {
                if (is == null) {
                    throw new IllegalStateException("Required resource /sql/fetch-probe.sql not found on classpath");
                }
                String ddl = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                stmt.execute(ddl);
            }
        }

        // 2. Bootstrap JPA EntityManagerFactory
        Map<String, Object> props = new HashMap<>();
        props.put("jakarta.persistence.jdbc.url", postgres.getJdbcUrl());
        props.put("jakarta.persistence.jdbc.user", postgres.getUsername());
        props.put("jakarta.persistence.jdbc.password", postgres.getPassword());
        props.put("jakarta.persistence.jdbc.driver", "org.postgresql.Driver");
        props.put("hibernate.connection.provider_disables_autocommit", "true");
        props.put("hibernate.generate_statistics", "true");
        props.put("hibernate.session_factory.statement_inspector", "com.aethercore.backend.fetch.FetchStatementInspector");
        props.put("hibernate.cache.use_second_level_cache", "false");
        props.put("hibernate.cache.use_query_cache", "false");
        props.put("hibernate.default_batch_fetch_size", "1");

        emf = Persistence.createEntityManagerFactory("aethercore-fetch-jpa-test", props);
    }

    @AfterAll
    static void tearDownAll() {
        if (emf != null && emf.isOpen()) {
            emf.close();
        }
    }

    private static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void clearTelemetry() {
        FetchStatementInspector.clear();
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
    }

    private static long getStatementCount() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        return stats.getPrepareStatementCount();
    }

    private static List<String> getCapturedSql() {
        return new ArrayList<>(FetchStatementInspector.getStatements());
    }

    private void seedD1() throws SQLException {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM fetch_order");
            stmt.executeUpdate("DELETE FROM fetch_buyer");
            for (int i = 1; i <= 6; i++) {
                stmt.executeUpdate("INSERT INTO fetch_buyer (id, name) VALUES (" + i + ", 'Buyer-" + i + "')");
            }
            for (int i = 1; i <= 6; i++) {
                stmt.executeUpdate("INSERT INTO fetch_order (id, buyer_id, quantity) VALUES (" + (100 + i) + ", " + i + ", " + i + ")");
            }
        }
    }

    private void seedD2() throws SQLException {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM fetch_order");
            stmt.executeUpdate("DELETE FROM fetch_buyer");
            for (int i = 1; i <= 3; i++) {
                stmt.executeUpdate("INSERT INTO fetch_buyer (id, name) VALUES (" + i + ", 'Buyer-" + i + "')");
            }
            int[] buyerIds = {1, 1, 2, 2, 3, 3};
            for (int i = 1; i <= 6; i++) {
                stmt.executeUpdate("INSERT INTO fetch_order (id, buyer_id, quantity) VALUES (" + (100 + i) + ", " + buyerIds[i - 1] + ", " + i + ")");
            }
        }
    }

    private void assertBusinessSummariesD1(List<OrderSummary> summaries) {
        assertEquals(6, summaries.size(), "Summaries must contain 6 items");
        for (int i = 0; i < 6; i++) {
            int idx = i + 1;
            OrderSummary s = summaries.get(i);
            assertEquals(100L + idx, s.orderId(), "Order ID must match 100 + " + idx);
            assertEquals("Buyer-" + idx, s.buyerName(), "Buyer name must match Buyer-" + idx);
            assertEquals(idx, s.quantity(), "Quantity must match " + idx);
        }
    }

    private void assertBusinessSummariesD2(List<OrderSummary> summaries) {
        assertEquals(6, summaries.size(), "Summaries must contain 6 items");
        int[] expectedBuyerIds = {1, 1, 2, 2, 3, 3};
        for (int i = 0; i < 6; i++) {
            int idx = i + 1;
            OrderSummary s = summaries.get(i);
            assertEquals(100L + idx, s.orderId(), "Order ID must match 100 + " + idx);
            assertEquals("Buyer-" + expectedBuyerIds[i], s.buyerName(), "Buyer name must match Buyer-" + expectedBuyerIds[i]);
            assertEquals(idx, s.quantity(), "Quantity must match " + idx);
        }
    }

    @Test
    @DisplayName("NQ1. Baseline N+1: Lazy ManyToOne association triggers 1 root query + 6 secondary queries")
    void nq1_baselineNPlusOne() throws SQLException {
        seedD1();
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            clearTelemetry();

            // 1. Root query
            TypedQuery<FetchOrder> query = em.createQuery("select o from FetchOrder o order by o.id", FetchOrder.class);
            List<FetchOrder> orders = query.getResultList();

            long rootCount = getStatementCount();
            assertEquals(1, rootCount, "Root query must execute exactly 1 prepared statement");
            assertEquals(6, orders.size(), "Order count must be 6");

            // Verify association is NOT loaded before accessing getter
            PersistenceUnitUtil puu = emf.getPersistenceUnitUtil();
            for (FetchOrder order : orders) {
                assertFalse(puu.isLoaded(order, "buyer"), "Association 'buyer' must not be loaded yet");
            }

            // 2. Iterate and read buyer.name -> triggers N secondary SELECTs
            List<OrderSummary> summaries = new ArrayList<>();
            for (FetchOrder order : orders) {
                summaries.add(new OrderSummary(order.getId(), order.getBuyer().getName(), order.getQuantity()));
            }

            long finalCount = getStatementCount();
            List<String> capturedSql = getCapturedSql();

            tx.commit();

            assertBusinessSummariesD1(summaries);
            assertEquals(7, finalCount, "Expected 1 root query + 6 secondary queries = 7 statements");
            assertEquals(7, capturedSql.size(), "Captured SQL count must be 7");
            assertTrue(capturedSql.get(0).toLowerCase().contains("fetch_order"), "First statement must query fetch_order");
            for (int i = 1; i <= 6; i++) {
                assertTrue(capturedSql.get(i).toLowerCase().contains("fetch_buyer"), "Secondary statements must query fetch_buyer");
            }

            observations.put("NQ1", new FetchCaseObservation(
                    "NQ1", "D1", "Baseline N+1", 6, 6,
                    (int) rootCount, (int) finalCount, capturedSql, summaries, true
            ));
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }
    }

    @Test
    @DisplayName("NQ2. JOIN FETCH: Single query loads FetchOrder and FetchBuyer eagerly")
    void nq2_joinFetch() throws SQLException {
        seedD1();
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            clearTelemetry();

            // 1. Query with JOIN FETCH
            TypedQuery<FetchOrder> query = em.createQuery("select o from FetchOrder o join fetch o.buyer order by o.id", FetchOrder.class);
            List<FetchOrder> orders = query.getResultList();

            long rootCount = getStatementCount();
            assertEquals(1, rootCount, "Root query with JOIN FETCH must execute exactly 1 prepared statement");
            assertEquals(6, orders.size(), "Order count must be 6");

            // Verify association IS loaded immediately
            PersistenceUnitUtil puu = emf.getPersistenceUnitUtil();
            for (FetchOrder order : orders) {
                assertTrue(puu.isLoaded(order, "buyer"), "Association 'buyer' must be loaded immediately via JOIN FETCH");
            }

            // 2. Iterate and read buyer.name -> should NOT trigger secondary SELECTs
            List<OrderSummary> summaries = new ArrayList<>();
            for (FetchOrder order : orders) {
                summaries.add(new OrderSummary(order.getId(), order.getBuyer().getName(), order.getQuantity()));
            }

            long finalCount = getStatementCount();
            List<String> capturedSql = getCapturedSql();

            tx.commit();

            assertBusinessSummariesD1(summaries);
            assertEquals(1, finalCount, "Expected exactly 1 statement for JOIN FETCH");
            assertEquals(1, capturedSql.size(), "Captured SQL count must be 1");
            String sql = capturedSql.get(0).toLowerCase();
            assertTrue(sql.contains("join") && sql.contains("fetch_buyer"), "SQL must contain a join to fetch_buyer");

            observations.put("NQ2", new FetchCaseObservation(
                    "NQ2", "D1", "JOIN FETCH", 6, 6,
                    (int) rootCount, (int) finalCount, capturedSql, summaries, true
            ));
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }
    }

    @Test
    @DisplayName("NQ3. Dynamic EntityGraph: jakarta.persistence.fetchgraph hint overrides lazy fetching to single query")
    void nq3_dynamicEntityGraph() throws SQLException {
        seedD1();
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            clearTelemetry();

            // 1. Create dynamic EntityGraph
            EntityGraph<FetchOrder> entityGraph = em.createEntityGraph(FetchOrder.class);
            entityGraph.addAttributeNodes("buyer");

            TypedQuery<FetchOrder> query = em.createQuery("select o from FetchOrder o order by o.id", FetchOrder.class);
            query.setHint("jakarta.persistence.fetchgraph", entityGraph);
            List<FetchOrder> orders = query.getResultList();

            long rootCount = getStatementCount();
            assertEquals(1, rootCount, "Root query with EntityGraph must execute exactly 1 prepared statement");
            assertEquals(6, orders.size(), "Order count must be 6");

            PersistenceUnitUtil puu = emf.getPersistenceUnitUtil();
            for (FetchOrder order : orders) {
                assertTrue(puu.isLoaded(order, "buyer"), "Association 'buyer' must be loaded via EntityGraph");
            }

            List<OrderSummary> summaries = new ArrayList<>();
            for (FetchOrder order : orders) {
                summaries.add(new OrderSummary(order.getId(), order.getBuyer().getName(), order.getQuantity()));
            }

            long finalCount = getStatementCount();
            List<String> capturedSql = getCapturedSql();

            tx.commit();

            assertBusinessSummariesD1(summaries);
            assertEquals(1, finalCount, "Expected exactly 1 statement for EntityGraph");
            assertEquals(1, capturedSql.size(), "Captured SQL count must be 1");
            String sql = capturedSql.get(0).toLowerCase();
            assertTrue(sql.contains("join") && sql.contains("fetch_buyer"), "SQL must contain join to fetch_buyer");

            observations.put("NQ3", new FetchCaseObservation(
                    "NQ3", "D1", "Dynamic EntityGraph", 6, 6,
                    (int) rootCount, (int) finalCount, capturedSql, summaries, true
            ));
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }
    }

    @Test
    @DisplayName("NQ4. JPQL Constructor Projection: Direct DTO instantiation bypasses entity lifecycle & proxies")
    void nq4_constructorProjection() throws SQLException {
        seedD1();
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        List<OrderSummary> summaries;
        long rootCount;
        long finalCount;
        List<String> capturedSql;
        try {
            tx.begin();
            clearTelemetry();

            String jpql = "select new com.aethercore.backend.fetch.OrderSummary(o.id, b.name, o.quantity) " +
                          "from FetchOrder o join o.buyer b " +
                          "order by o.id";
            TypedQuery<OrderSummary> query = em.createQuery(jpql, OrderSummary.class);
            summaries = query.getResultList();

            rootCount = getStatementCount();
            finalCount = rootCount;
            capturedSql = getCapturedSql();

            tx.commit();
        } finally {
            if (tx.isActive()) tx.rollback();
            em.close();
        }

        // Verify DTO fields remain accessible after closing the EntityManager
        assertFalse(em.isOpen(), "EntityManager must be closed");
        assertBusinessSummariesD1(summaries);
        assertEquals(1, rootCount, "Constructor projection must execute exactly 1 statement");
        assertEquals(1, finalCount, "Final count must be 1 statement");
        assertEquals(1, capturedSql.size(), "Captured SQL count must be 1");
        String sql = capturedSql.get(0).toLowerCase();
        assertTrue(sql.contains("join") && sql.contains("fetch_buyer"), "SQL must contain join to fetch_buyer");

        observations.put("NQ4", new FetchCaseObservation(
                "NQ4", "D1", "JPQL Constructor Projection", 6, 6,
                (int) rootCount, (int) finalCount, capturedSql, summaries, true
        ));
    }

    @Test
    @DisplayName("NQ5. Repeated Buyers with First-Level Cache: N is bounded by distinct buyers (1 root + 3 secondary = 4 statements)")
    void nq5_firstLevelCacheRepeatedBuyers() throws SQLException {
        seedD2();
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            clearTelemetry();

            // 1. Root query
            TypedQuery<FetchOrder> query = em.createQuery("select o from FetchOrder o order by o.id", FetchOrder.class);
            List<FetchOrder> orders = query.getResultList();

            long rootCount = getStatementCount();
            assertEquals(1, rootCount, "Root query must execute exactly 1 prepared statement");
            assertEquals(6, orders.size(), "Order count must be 6");

            PersistenceUnitUtil puu = emf.getPersistenceUnitUtil();
            for (FetchOrder order : orders) {
                assertFalse(puu.isLoaded(order, "buyer"), "Association 'buyer' must not be loaded initially");
            }

            // 2. Iterate and read buyer.name
            // Buyers are [1, 1, 2, 2, 3, 3]
            // Accessing buyer 1 on order 101 loads buyer 1.
            // Accessing buyer 1 on order 102 resolves from 1st-level cache (no statement).
            // Same for buyer 2 and buyer 3.
            List<OrderSummary> summaries = new ArrayList<>();
            for (FetchOrder order : orders) {
                summaries.add(new OrderSummary(order.getId(), order.getBuyer().getName(), order.getQuantity()));
            }

            long finalCount = getStatementCount();
            List<String> capturedSql = getCapturedSql();

            tx.commit();

            assertBusinessSummariesD2(summaries);
            assertEquals(4, finalCount, "Expected 1 root + 3 distinct buyer queries = 4 statements due to 1st-level cache");
            assertEquals(4, capturedSql.size(), "Captured SQL count must be 4");

            observations.put("NQ5", new FetchCaseObservation(
                    "NQ5", "D2", "Baseline N+1 with First-Level Cache", 6, 3,
                    (int) rootCount, (int) finalCount, capturedSql, summaries, true
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

        File file = new File(dir, "fetch-observations.json");
        String runId = System.getProperty("lab.runId", "ad-hoc-" + Instant.now().toEpochMilli());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId);
        String pgVer = "PostgreSQL " + (postgresServerVersion != null ? postgresServerVersion : "16-alpine");
        String hbVer = "Hibernate " + org.hibernate.Version.getVersionString();
        payload.put("environment", "Spring Boot 3.3.4 / " + hbVer + " / " + pgVer);
        payload.put("timestamp", Instant.now().toString());

        List<String> keys = new ArrayList<>(observations.keySet());
        Collections.sort(keys);
        List<FetchCaseObservation> scenarios = new ArrayList<>();
        for (String k : keys) {
            scenarios.add(observations.get(k));
        }
        payload.put("scenarios", scenarios);

        try {
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(file, payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to export fetch observations to " + file.getAbsolutePath(), e);
        }
    }

    public record FetchCaseObservation(
            String caseId,
            String datasetId,
            String strategy,
            int orderCount,
            int distinctBuyerCount,
            int preparedStatementsAfterRootQuery,
            int preparedStatementsAfterMapping,
            List<String> sqlStatements,
            List<OrderSummary> resultRows,
            boolean assertionsPassed
    ) {}
}
