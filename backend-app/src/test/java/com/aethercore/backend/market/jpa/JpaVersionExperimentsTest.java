package com.aethercore.backend.market.jpa;

import jakarta.persistence.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2-E1C: Standalone JPA Test Lab (@Version, Persistence Context, Flush vs Commit).
 * Bootstraps Java SE JPA with EntityManagerFactory (RESOURCE_LOCAL, Hibernate 6.5)
 * against a real PostgreSQL 16 Testcontainers instance.
 *
 * Verifies:
 * - JPA-1: Persistence context identity & dirty checking issuing versioned UPDATE.
 * - JPA-2: OptimisticLockException detection at explicit flush across two overlapping persistence contexts.
 * - JPA-3: Optimistic conflict detection at commit boundary without explicit flush.
 * - JPA-4: Flush is not commit (transaction isolation visibility & clean rollback).
 * - JPA-5: Caller-driven retry with a fresh EntityManager/EntityTransaction.
 * - JPA-6: Transaction atomicity when purchase insert fails (duplicate UUID) after stock flush.
 */
@Testcontainers
public class JpaVersionExperimentsTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aethercore_jpa_test")
            .withUsername("test")
            .withPassword("test");

    private static EntityManagerFactory emf;

    @BeforeAll
    static void setUpAll() {
        // 1. Run existing Flyway migrations V1..V4 to prepare database schema
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load();
        flyway.migrate();

        // 2. Bootstrap JPA EntityManagerFactory in Java SE mode
        Map<String, Object> props = new HashMap<>();
        props.put("jakarta.persistence.jdbc.url", postgres.getJdbcUrl());
        props.put("jakarta.persistence.jdbc.user", postgres.getUsername());
        props.put("jakarta.persistence.jdbc.password", postgres.getPassword());
        props.put("jakarta.persistence.jdbc.driver", "org.postgresql.Driver");
        props.put("hibernate.connection.provider_disables_autocommit", "true");

        emf = Persistence.createEntityManagerFactory("aethercore-market-jpa-test", props);
    }

    @AfterAll
    static void tearDownAll() {
        if (emf != null && emf.isOpen()) {
            emf.close();
        }
    }

    @BeforeEach
    void cleanTables() throws SQLException {
        TestStatementInspector.clear();
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM market_purchase");
            stmt.executeUpdate("DELETE FROM market_stock");
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private void seedStock(String sku, long available) throws SQLException {
        try (Connection conn = getConnection();
             var ps = conn.prepareStatement("INSERT INTO market_stock (sku, available, version) VALUES (?, ?, 0)")) {
            ps.setString(1, sku);
            ps.setLong(2, available);
            ps.executeUpdate();
        }
    }

    private long getStock(String sku) throws SQLException {
        try (Connection conn = getConnection();
             var ps = conn.prepareStatement("SELECT available FROM market_stock WHERE sku = ?")) {
            ps.setString(1, sku);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return -1L;
    }

    private long getVersion(String sku) throws SQLException {
        try (Connection conn = getConnection();
             var ps = conn.prepareStatement("SELECT version FROM market_stock WHERE sku = ?")) {
            ps.setString(1, sku);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return -1L;
    }

    private int getPurchaseCount(String sku) throws SQLException {
        try (Connection conn = getConnection();
             var ps = conn.prepareStatement("SELECT COUNT(*) FROM market_purchase WHERE sku = ?")) {
            ps.setString(1, sku);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 0;
    }

    private long getSumPurchasedQuantity(String sku) throws SQLException {
        try (Connection conn = getConnection();
             var ps = conn.prepareStatement("SELECT COALESCE(SUM(quantity), 0) FROM market_purchase WHERE sku = ?")) {
            ps.setString(1, sku);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0L;
    }

    private void safeShutdownExecutor(ExecutorService executor, CountDownLatch gate, List<? extends Future<?>> futures) {
        if (gate != null) {
            gate.countDown();
        }
        if (futures != null) {
            for (Future<?> f : futures) {
                if (f != null && !f.isDone()) {
                    f.cancel(true);
                }
            }
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    fail("Worker threads failed to terminate within cleanup timeout");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            throw new AssertionError("Cleanup interrupted while waiting for worker termination", e);
        }
    }

    @Test
    @DisplayName("JPA-1. Persistence context identity and dirty checking: Hibernate issues versioned UPDATE on commit")
    void jpa1_persistenceContextAndDirtyChecking() throws Exception {
        String sku = "GOLD_INGOT";
        seedStock(sku, 5L);

        EntityManager em = emf.createEntityManager();
        UUID purchaseId = UUID.randomUUID();

        try {
            em.getTransaction().begin();

            // First-level cache test: two find calls return identical managed Java object
            JpaMarketStock s1 = em.find(JpaMarketStock.class, sku);
            JpaMarketStock s2 = em.find(JpaMarketStock.class, sku);
            assertSame(s1, s2, "Persistence context must return identical entity reference on heap");
            assertEquals(0L, s1.getVersion(), "Initial version is 0");

            // Mutate entity state; do NOT call any setter on version
            s1.decrementStock(2);
            em.persist(new JpaMarketPurchase(purchaseId, sku, 2));

            // Commit transaction: Hibernate runs dirty checking, generates UPDATE with version check, and increments version
            em.getTransaction().commit();
        } finally {
            em.close();
        }

        // Verify final state
        assertEquals(3L, getStock(sku));
        assertEquals(1L, getVersion(sku), "Hibernate must automatically advance version to 1");
        assertEquals(1, getPurchaseCount(sku));
        assertEquals(2L, getSumPurchasedQuantity(sku));

        // Verify generated SQL via StatementInspector: assert version in SET, version predicate in WHERE, and sku in WHERE
        List<String> sqls = TestStatementInspector.getStatements();
        boolean foundVersionedUpdate = false;
        for (String sql : sqls) {
            String normalized = sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
            if (normalized.startsWith("update market_stock set") && normalized.contains("where")) {
                String[] parts = normalized.split("\\bwhere\\b");
                if (parts.length == 2) {
                    String setClause = parts[0];
                    String whereClause = parts[1];
                    boolean hasVersionInSet = setClause.contains("version=?") || setClause.contains("version = ?");
                    boolean hasVersionInWhere = whereClause.contains("version=?") || whereClause.contains("version = ?");
                    boolean hasSkuInWhere = whereClause.contains("sku=?") || whereClause.contains("sku = ?");
                    if (hasVersionInSet && hasVersionInWhere && hasSkuInWhere) {
                        foundVersionedUpdate = true;
                        break;
                    }
                }
            }
        }
        assertTrue(foundVersionedUpdate, "Hibernate must emit UPDATE market_stock with version in SET, version predicate in WHERE, and sku in WHERE");
    }

    @Test
    @DisplayName("JPA-2. Two persistence contexts, same version: OptimisticLockException at explicit flush")
    void jpa2_twoPersistenceContexts_conflictAtExplicitFlush() throws Exception {
        String sku = "NETHER_STAR";
        seedStock(sku, 2L);

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        CountDownLatch w1DoneLatch = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            // Worker 1: reads version 0, flushes and commits first
            Future<Boolean> f1 = executor.submit(() -> {
                boolean gateOpen = startGate.await(5, TimeUnit.SECONDS);
                assertTrue(gateOpen);
                EntityManager em1 = emf.createEntityManager();
                try {
                    em1.getTransaction().begin();
                    JpaMarketStock s1 = em1.find(JpaMarketStock.class, sku);
                    assertEquals(0L, s1.getVersion());

                    readBarrier.await(5, TimeUnit.SECONDS);

                    s1.decrementStock(1);
                    em1.persist(new JpaMarketPurchase(UUID.randomUUID(), sku, 1));
                    em1.flush();
                    em1.getTransaction().commit();
                    return true;
                } finally {
                    w1DoneLatch.countDown();
                    em1.close();
                }
            });
            futures.add(f1);

            // Worker 2: reads version 0, waits for Worker 1 to commit, then flushes -> OptimisticLockException
            Future<Boolean> f2 = executor.submit(() -> {
                boolean gateOpen = startGate.await(5, TimeUnit.SECONDS);
                assertTrue(gateOpen);
                EntityManager em2 = emf.createEntityManager();
                try {
                    em2.getTransaction().begin();
                    JpaMarketStock s2 = em2.find(JpaMarketStock.class, sku);
                    assertEquals(0L, s2.getVersion());

                    readBarrier.await(5, TimeUnit.SECONDS);

                    boolean w1Done = w1DoneLatch.await(5, TimeUnit.SECONDS);
                    assertTrue(w1Done, "Worker 1 must finish before Worker 2 flushes");

                    s2.decrementStock(1);
                    em2.persist(new JpaMarketPurchase(UUID.randomUUID(), sku, 1));

                    // Explicit flush must detect optimistic conflict
                    em2.flush();
                    em2.getTransaction().commit();
                    return true;
                } catch (Exception ex) {
                    if (em2.getTransaction().isActive()) {
                        em2.getTransaction().rollback();
                    }
                    throw ex;
                } finally {
                    em2.close();
                }
            });
            futures.add(f2);

            startGate.countDown();

            assertTrue(f1.get(5, TimeUnit.SECONDS), "Worker 1 must commit successfully");

            ExecutionException ex = assertThrows(ExecutionException.class, () -> f2.get(5, TimeUnit.SECONDS));
            Throwable root = ex.getCause();
            boolean isOptimisticConflict = (root instanceof OptimisticLockException) ||
                    (root != null && root.getCause() instanceof OptimisticLockException);
            assertTrue(isOptimisticConflict, "Worker 2 must fail specifically with OptimisticLockException at explicit flush()");

            // Only Worker 1's purchase is committed
            assertEquals(1L, getStock(sku));
            assertEquals(1L, getVersion(sku));
            assertEquals(1, getPurchaseCount(sku));
            assertEquals(1L, getSumPurchasedQuantity(sku));
        } finally {
            safeShutdownExecutor(executor, startGate, futures);
        }
    }

    @Test
    @DisplayName("JPA-3. Conflict at commit boundary when no explicit flush is called")
    void jpa3_conflictAtCommitBoundary_withoutExplicitFlush() throws Exception {
        String sku = "BEACON";
        seedStock(sku, 2L);

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        CountDownLatch w1DoneLatch = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            Future<Boolean> f1 = executor.submit(() -> {
                boolean gateOpen = startGate.await(5, TimeUnit.SECONDS);
                assertTrue(gateOpen);
                EntityManager em1 = emf.createEntityManager();
                try {
                    em1.getTransaction().begin();
                    JpaMarketStock s1 = em1.find(JpaMarketStock.class, sku);
                    assertEquals(0L, s1.getVersion());
                    readBarrier.await(5, TimeUnit.SECONDS);

                    s1.decrementStock(1);
                    em1.persist(new JpaMarketPurchase(UUID.randomUUID(), sku, 1));
                    em1.getTransaction().commit();
                    return true;
                } finally {
                    w1DoneLatch.countDown();
                    em1.close();
                }
            });
            futures.add(f1);

            Future<Boolean> f2 = executor.submit(() -> {
                boolean gateOpen = startGate.await(5, TimeUnit.SECONDS);
                assertTrue(gateOpen);
                EntityManager em2 = emf.createEntityManager();
                try {
                    em2.getTransaction().begin();
                    JpaMarketStock s2 = em2.find(JpaMarketStock.class, sku);
                    assertEquals(0L, s2.getVersion());
                    readBarrier.await(5, TimeUnit.SECONDS);

                    boolean w1Done = w1DoneLatch.await(5, TimeUnit.SECONDS);
                    assertTrue(w1Done);

                    // Mutate state without calling flush()
                    s2.decrementStock(1);
                    em2.persist(new JpaMarketPurchase(UUID.randomUUID(), sku, 1));

                    // Commit boundary triggers automatic flush and detects conflict
                    em2.getTransaction().commit();
                    return true;
                } catch (Exception ex) {
                    if (em2.getTransaction().isActive()) {
                        em2.getTransaction().rollback();
                    }
                    throw ex;
                } finally {
                    em2.close();
                }
            });
            futures.add(f2);

            startGate.countDown();

            assertTrue(f1.get(5, TimeUnit.SECONDS));

            ExecutionException ex = assertThrows(ExecutionException.class, () -> f2.get(5, TimeUnit.SECONDS));
            Throwable cause = ex.getCause();
            // In resource-local JPA, commit failure throws RollbackException wrapping OptimisticLockException
            assertInstanceOf(RollbackException.class, cause, "Commit failure must throw RollbackException at commit boundary");
            boolean hasOptimisticInChain = false;
            Throwable cur = cause;
            while (cur != null) {
                if (cur instanceof OptimisticLockException) {
                    hasOptimisticInChain = true;
                    break;
                }
                cur = cur.getCause();
            }
            assertTrue(hasOptimisticInChain, "Commit boundary RollbackException must have OptimisticLockException in cause chain");

            assertEquals(1L, getStock(sku));
            assertEquals(1L, getVersion(sku));
            assertEquals(1, getPurchaseCount(sku));
        } finally {
            safeShutdownExecutor(executor, startGate, futures);
        }
    }

    @Test
    @DisplayName("JPA-4. Flush is NOT commit: flushed dirty changes are not visible to observer and roll back completely")
    void jpa4_flushIsNotCommit_uncommittedDataInvisibleAndRollsBack() throws Exception {
        String sku = "DRAGON_HEAD";
        seedStock(sku, 2L);

        EntityManager em = emf.createEntityManager();
        CountDownLatch flushedLatch = new CountDownLatch(1);
        CountDownLatch observerDoneLatch = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(1);
        Future<Boolean> observerFuture = null;

        try {
            observerFuture = executor.submit(() -> {
                boolean flushed = flushedLatch.await(5, TimeUnit.SECONDS);
                assertTrue(flushed);

                // Observer on independent connection under READ COMMITTED
                long observedStock = getStock(sku);
                long observedVersion = getVersion(sku);
                int observedPurchases = getPurchaseCount(sku);

                assertEquals(2L, observedStock, "Uncommitted flush must NOT be visible to outside connection");
                assertEquals(0L, observedVersion, "Uncommitted version must NOT be visible");
                assertEquals(0, observedPurchases, "Uncommitted purchase insert must NOT be visible");

                observerDoneLatch.countDown();
                return true;
            });

            em.getTransaction().begin();
            JpaMarketStock stock = em.find(JpaMarketStock.class, sku);
            stock.decrementStock(1);
            em.persist(new JpaMarketPurchase(UUID.randomUUID(), sku, 1));

            // Flush sends SQL statements to PostgreSQL without committing
            em.flush();
            flushedLatch.countDown();

            boolean observerDone = observerDoneLatch.await(5, TimeUnit.SECONDS);
            assertTrue(observerDone);
            assertTrue(observerFuture.get(5, TimeUnit.SECONDS), "Observer thread must complete without assertion failures");

            // Now rollback the transaction
            em.getTransaction().rollback();
        } finally {
            em.close();
            safeShutdownExecutor(executor, flushedLatch, observerFuture != null ? List.of(observerFuture) : null);
        }

        // After rollback, verify database state remains at baseline
        assertEquals(2L, getStock(sku), "Stock restored to 2 after rollback");
        assertEquals(0L, getVersion(sku), "Version restored to 0 after rollback");
        assertEquals(0, getPurchaseCount(sku), "0 purchases recorded");
    }

    @Test
    @DisplayName("JPA-5. Retry with a fresh EntityManager/Transaction succeeds after conflict")
    void jpa5_retryWithFreshEntityManager_succeeds() throws Exception {
        String sku = "CONDUIT";
        seedStock(sku, 2L);

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        CountDownLatch w1DoneLatch = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            Future<Boolean> f1 = executor.submit(() -> {
                boolean gateOpen = startGate.await(5, TimeUnit.SECONDS);
                assertTrue(gateOpen);
                EntityManager em1 = emf.createEntityManager();
                try {
                    em1.getTransaction().begin();
                    JpaMarketStock s1 = em1.find(JpaMarketStock.class, sku);
                    readBarrier.await(5, TimeUnit.SECONDS);
                    s1.decrementStock(1);
                    em1.persist(new JpaMarketPurchase(UUID.randomUUID(), sku, 1));
                    em1.getTransaction().commit();
                    return true;
                } finally {
                    w1DoneLatch.countDown();
                    em1.close();
                }
            });
            futures.add(f1);

            UUID retryPurchaseId = UUID.randomUUID();
            Future<Boolean> f2 = executor.submit(() -> {
                boolean gateOpen = startGate.await(5, TimeUnit.SECONDS);
                assertTrue(gateOpen);
                int attempts = 0;
                boolean attempt1HadOptimisticConflict = false;

                // Attempt 1: Must encounter OptimisticLockException specifically
                attempts++;
                EntityManager em2 = emf.createEntityManager();
                try {
                    em2.getTransaction().begin();
                    JpaMarketStock s2 = em2.find(JpaMarketStock.class, sku);
                    readBarrier.await(5, TimeUnit.SECONDS);

                    boolean w1Done = w1DoneLatch.await(5, TimeUnit.SECONDS);
                    assertTrue(w1Done);

                    s2.decrementStock(1);
                    em2.persist(new JpaMarketPurchase(retryPurchaseId, sku, 1));
                    em2.flush(); // Throws OptimisticLockException
                    em2.getTransaction().commit();
                    fail("Attempt 1 must throw OptimisticLockException on flush");
                } catch (OptimisticLockException ole) {
                    attempt1HadOptimisticConflict = true;
                    if (em2.getTransaction().isActive()) {
                        em2.getTransaction().rollback();
                    }
                } catch (Throwable t) {
                    boolean foundOptimistic = false;
                    Throwable cur = t;
                    while (cur != null) {
                        if (cur instanceof OptimisticLockException) {
                            foundOptimistic = true;
                            break;
                        }
                        cur = cur.getCause();
                    }
                    if (foundOptimistic) {
                        attempt1HadOptimisticConflict = true;
                        if (em2.getTransaction().isActive()) {
                            em2.getTransaction().rollback();
                        }
                    } else {
                        // Connection/timeout/constraint error - do NOT proceed to retry
                        if (em2.getTransaction().isActive()) {
                            em2.getTransaction().rollback();
                        }
                        throw t;
                    }
                } finally {
                    em2.close();
                }

                assertTrue(attempt1HadOptimisticConflict, "Attempt 1 must fail specifically with OptimisticLockException");

                // Attempt 2: RETRY with a brand-new EntityManager and new Transaction
                attempts++;
                EntityManager emRetry = emf.createEntityManager();
                try {
                    emRetry.getTransaction().begin();
                    JpaMarketStock freshStock = emRetry.find(JpaMarketStock.class, sku);
                    assertEquals(1L, freshStock.getVersion(), "Fresh EM reads committed version 1");
                    assertEquals(1L, freshStock.getAvailable(), "Fresh EM reads committed available 1");

                    freshStock.decrementStock(1);
                    emRetry.persist(new JpaMarketPurchase(retryPurchaseId, sku, 1));
                    emRetry.getTransaction().commit();
                } catch (Exception ex) {
                    if (emRetry.getTransaction().isActive()) {
                        emRetry.getTransaction().rollback();
                    }
                    throw ex;
                } finally {
                    emRetry.close();
                }

                assertEquals(2, attempts, "Exactly two attempts must be executed in this retry scenario");
                return true;
            });
            futures.add(f2);

            startGate.countDown();

            assertTrue(f1.get(5, TimeUnit.SECONDS));
            assertTrue(f2.get(5, TimeUnit.SECONDS));

            assertEquals(0L, getStock(sku));
            assertEquals(2L, getVersion(sku), "Version is 2 after initial purchase and successful retry");
            assertEquals(2, getPurchaseCount(sku));
            assertEquals(2L, getSumPurchasedQuantity(sku));
            assertEquals(2L, getStock(sku) + getSumPurchasedQuantity(sku), "Invariant holds");
        } finally {
            safeShutdownExecutor(executor, startGate, futures);
        }
    }

    @Test
    @DisplayName("JPA-6. Transaction atomicity: Purchase insert failure (duplicate UUID) after stock flush restores baseline")
    void jpa6_atomicity_purchaseInsertFailureAfterStockFlush_restoresBaseline() throws Exception {
        String sku = "HEART_OF_THE_SEA";
        seedStock(sku, 2L);

        UUID fixedPurchaseId = UUID.randomUUID();

        // Initial purchase
        EntityManager em1 = emf.createEntityManager();
        try {
            em1.getTransaction().begin();
            JpaMarketStock stock = em1.find(JpaMarketStock.class, sku);
            stock.decrementStock(1);
            em1.persist(new JpaMarketPurchase(fixedPurchaseId, sku, 1));
            em1.getTransaction().commit();
        } finally {
            em1.close();
        }

        assertEquals(1L, getStock(sku));
        assertEquals(1L, getVersion(sku));
        assertEquals(1, getPurchaseCount(sku));

        // Fault injection: decrement stock, flush, then persist duplicate purchaseId
        EntityManager em2 = emf.createEntityManager();
        boolean failedWithPkConflict = false;
        try {
            em2.getTransaction().begin();
            JpaMarketStock stock = em2.find(JpaMarketStock.class, sku);
            stock.decrementStock(1);
            em2.flush(); // Stock update is flushed to DB

            em2.persist(new JpaMarketPurchase(fixedPurchaseId, sku, 1)); // Duplicate UUID PK
            em2.getTransaction().commit();
        } catch (Exception ex) {
            failedWithPkConflict = true;
            if (em2.getTransaction().isActive()) {
                em2.getTransaction().rollback();
            }

            // Verify SQLSTATE 23505 in cause chain
            boolean found23505 = false;
            Throwable cur = ex;
            while (cur != null) {
                if (cur instanceof SQLException sqlEx && "23505".equals(sqlEx.getSQLState())) {
                    found23505 = true;
                    break;
                }
                cur = cur.getCause();
            }
            assertTrue(found23505, "SQLException cause chain must contain SQLSTATE 23505 (unique_violation)");
        } finally {
            em2.close();
        }

        assertTrue(failedWithPkConflict, "Duplicate PK insertion must cause transaction failure");

        // Verify complete rollback: stock is restored to 1, version remains 1, count remains 1
        assertEquals(1L, getStock(sku), "Stock restored to 1");
        assertEquals(1L, getVersion(sku), "Version remains 1");
        assertEquals(1, getPurchaseCount(sku), "Purchase count remains 1");
        assertEquals(1L, getSumPurchasedQuantity(sku), "Sum quantity remains 1");
        assertEquals(2L, getStock(sku) + getSumPurchasedQuantity(sku), "Stock invariant preserved");
    }
}
