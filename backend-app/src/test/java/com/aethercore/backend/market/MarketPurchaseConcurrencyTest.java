package com.aethercore.backend.market;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency and Transaction Integration Test for Market Purchase (M2-E1A).
 * Uses real PostgreSQL container via Testcontainers with monotonic nanoTime deadlines,
 * multi-phase executor termination cleanup, typed exception and SQLSTATE verification,
 * and session timeout assertions measured on worker connections.
 */
@SpringBootTest
@Testcontainers
public class MarketPurchaseConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aethercore_market_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.sql.init.mode", () -> "always");

        // Enforce finite timeouts on test DataSource connections
        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000"); // 5s connection acquisition
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "4");
        // Apply session timeouts for PostgreSQL: lock_timeout 5s, statement_timeout 10s
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET lock_timeout = '5s'; SET statement_timeout = '10s';");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private FixedMarketPurchaseService fixedService;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM market_purchase");
        jdbcTemplate.update("DELETE FROM market_stock");
    }

    private void seedStock(String sku, long initialAvailable) {
        jdbcTemplate.update("INSERT INTO market_stock (sku, available) VALUES (?, ?)", sku, initialAvailable);
    }

    private long getStock(String sku) {
        Long val = jdbcTemplate.queryForObject("SELECT available FROM market_stock WHERE sku = ?", Long.class, sku);
        return val != null ? val : 0L;
    }

    private int getPurchaseCount(String sku) {
        Integer val = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM market_purchase WHERE sku = ?", Integer.class, sku);
        return val != null ? val : 0;
    }

    private long getSumPurchasedQuantity(String sku) {
        Long val = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM market_purchase WHERE sku = ?", Long.class, sku);
        return val != null ? val : 0L;
    }

    private void safeShutdownExecutor(ExecutorService executor, CountDownLatch gate, List<? extends Future<?>> futures) {
        if (gate != null) {
            gate.countDown();
        }
        if (futures != null) {
            for (Future<?> f : futures) {
                if (!f.isDone()) {
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
    @DisplayName("Verify PostgreSQL session timeouts are active on worker connections")
    void verifySessionTimeoutsActive() {
        String lockTimeout = jdbcTemplate.queryForObject("SHOW lock_timeout", String.class);
        String statementTimeout = jdbcTemplate.queryForObject("SHOW statement_timeout", String.class);
        assertEquals("5s", lockTimeout, "lock_timeout must be configured to 5s");
        assertEquals("10s", statementTimeout, "statement_timeout must be configured to 10s");
    }

    @Test
    @DisplayName("B2. Broken Service: two concurrent buyers on stock 1 reproduce lost update and violate invariant")
    void brokenService_twoBuyersOnOneStock_reproducesLostUpdate() throws Exception {
        String sku = "REDSTONE";
        seedStock(sku, 1L);

        CyclicBarrier barrier = new CyclicBarrier(2);
        BrokenMarketPurchaseService brokenService = new BrokenMarketPurchaseService(jdbcTemplate, transactionManager, barrier);

        List<BrokenMarketPurchaseService.WorkerObservation> observations = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<PurchaseResult>> futures = new ArrayList<>();

        try {
            futures.add(executor.submit(() -> brokenService.purchaseWithObservation(UUID.randomUUID(), sku, 1, observations::add)));
            futures.add(executor.submit(() -> brokenService.purchaseWithObservation(UUID.randomUUID(), sku, 1, observations::add)));

            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            for (Future<PurchaseResult> f : futures) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new TimeoutException("Global monotonic deadline expired waiting for worker future");
                }
                PurchaseResult r = f.get(remainingNanos, TimeUnit.NANOSECONDS);
                assertEquals(PurchaseResult.SUCCESS, r);
            }

            // Verify observations measured on each worker's own connection inside transaction
            assertEquals(2, observations.size());
            assertNotEquals(observations.get(0).backendPid, observations.get(1).backendPid, "Must run in two distinct backend sessions");
            assertEquals("read committed", observations.get(0).isolation);
            assertEquals("read committed", observations.get(1).isolation);
            assertEquals("5s", observations.get(0).lockTimeout, "Worker 1 has lock_timeout 5s");
            assertEquals("5s", observations.get(1).lockTimeout, "Worker 2 has lock_timeout 5s");
            assertEquals("10s", observations.get(0).statementTimeout, "Worker 1 has statement_timeout 10s");
            assertEquals("10s", observations.get(1).statementTimeout, "Worker 2 has statement_timeout 10s");
            assertEquals(1L, observations.get(0).readAvailable, "Worker 1 read initial available 1");
            assertEquals(1L, observations.get(1).readAvailable, "Worker 2 read stale available 1");

            long finalStock = getStock(sku);
            long totalQuantity = getSumPurchasedQuantity(sku);
            int purchaseCount = getPurchaseCount(sku);

            assertEquals(0L, finalStock, "Final stock is 0");
            assertEquals(2, purchaseCount, "2 purchases were recorded");
            assertEquals(2L, totalQuantity, "Total quantity purchased is 2");

            // INVARIANT VIOLATION: finalStock (0) + totalQuantity (2) != initialStock (1)
            assertNotEquals(1L, finalStock + totalQuantity, "Broken service violates invariant due to lost update");
        } finally {
            safeShutdownExecutor(executor, null, futures);
        }
    }

    @Test
    @DisplayName("B3. Fixed Service: two concurrent buyers on stock 1 preserve invariant (1 SUCCESS, 1 NOT_AVAILABLE)")
    void fixedService_twoBuyersOnOneStock_preservesInvariant() throws Exception {
        String sku = "REDSTONE";
        seedStock(sku, 1L);

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<PurchaseResult>> futures = new ArrayList<>();

        try {
            Callable<PurchaseResult> task = () -> {
                readyLatch.countDown();
                boolean opened = startGate.await(5, TimeUnit.SECONDS);
                if (!opened) {
                    throw new IllegalStateException("startGate timed out waiting to open");
                }
                return fixedService.purchase(UUID.randomUUID(), sku, 1);
            };

            futures.add(executor.submit(task));
            futures.add(executor.submit(task));

            // Ensure both workers are ready before countdown
            boolean allReady = readyLatch.await(5, TimeUnit.SECONDS);
            assertTrue(allReady, "Both workers must be ready before gate release");
            startGate.countDown();

            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            List<PurchaseResult> results = new ArrayList<>();
            for (Future<PurchaseResult> f : futures) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new TimeoutException("Global monotonic deadline expired waiting for worker future");
                }
                results.add(f.get(remainingNanos, TimeUnit.NANOSECONDS));
            }

            long successCount = results.stream().filter(r -> r == PurchaseResult.SUCCESS).count();
            long notAvailableCount = results.stream().filter(r -> r == PurchaseResult.NOT_AVAILABLE).count();

            assertEquals(1L, successCount, "Exactly 1 purchase must succeed");
            assertEquals(1L, notAvailableCount, "Exactly 1 purchase must be rejected as NOT_AVAILABLE");

            long finalStock = getStock(sku);
            long totalQuantity = getSumPurchasedQuantity(sku);
            int purchaseCount = getPurchaseCount(sku);

            assertEquals(0L, finalStock);
            assertEquals(1, purchaseCount);
            assertEquals(1L, totalQuantity);
            assertEquals(1L, finalStock + totalQuantity, "Invariant holds: finalStock (0) + totalQuantity (1) == initialStock (1)");
        } finally {
            safeShutdownExecutor(executor, startGate, futures);
        }
    }

    @Test
    @DisplayName("B4. Sequential multiple units purchase correctly updates stock")
    void fixedService_sequentialMultipleUnits_updatesStockCorrectly() {
        String sku = "GLOWSTONE";
        seedStock(sku, 5L);

        PurchaseResult r1 = fixedService.purchase(UUID.randomUUID(), sku, 2);
        assertEquals(PurchaseResult.SUCCESS, r1);
        assertEquals(3L, getStock(sku));

        PurchaseResult r2 = fixedService.purchase(UUID.randomUUID(), sku, 3);
        assertEquals(PurchaseResult.SUCCESS, r2);
        assertEquals(0L, getStock(sku));

        assertEquals(2, getPurchaseCount(sku));
        assertEquals(5L, getSumPurchasedQuantity(sku));
    }

    @Test
    @DisplayName("B4. Zero or negative quantity is rejected with IllegalArgumentException without altering DB")
    void fixedService_invalidQuantity_throwsException() {
        String sku = "LAPIS";
        seedStock(sku, 10L);

        assertThrows(IllegalArgumentException.class, () -> fixedService.purchase(UUID.randomUUID(), sku, 0));
        assertThrows(IllegalArgumentException.class, () -> fixedService.purchase(UUID.randomUUID(), sku, -5));

        assertEquals(10L, getStock(sku));
        assertEquals(0, getPurchaseCount(sku));
    }

    @Test
    @DisplayName("B4. Missing SKU or insufficient stock returns NOT_AVAILABLE without purchase record")
    void fixedService_insufficientStockOrMissingSku_returnsNotAvailable() {
        String sku = "DIAMOND";
        seedStock(sku, 2L);

        // Insufficient stock
        PurchaseResult r1 = fixedService.purchase(UUID.randomUUID(), sku, 5);
        assertEquals(PurchaseResult.NOT_AVAILABLE, r1);
        assertEquals(2L, getStock(sku));
        assertEquals(0, getPurchaseCount(sku));

        // Non-existent SKU
        PurchaseResult r2 = fixedService.purchase(UUID.randomUUID(), "EMERALD", 1);
        assertEquals(PurchaseResult.NOT_AVAILABLE, r2);
        assertEquals(0, getPurchaseCount("EMERALD"));
    }

    @Test
    @DisplayName("B4. Rollback on purchase insert failure (duplicate UUID) restores stock atomically")
    void fixedService_rollbackOnInsertFailure_restoresStock() {
        String sku = "NETHERITE";
        seedStock(sku, 2L);

        UUID fixedPurchaseId = UUID.randomUUID();

        // 1. Initial successful purchase
        PurchaseResult r1 = fixedService.purchase(fixedPurchaseId, sku, 1);
        assertEquals(PurchaseResult.SUCCESS, r1);
        assertEquals(1L, getStock(sku));
        assertEquals(1, getPurchaseCount(sku));

        // 2. Fault injection: Attempt duplicate UUID insertion, causing PK collision on market_purchase
        DuplicateKeyException ex = assertThrows(DuplicateKeyException.class, () -> {
            fixedService.purchase(fixedPurchaseId, sku, 1);
        });

        // 3. Verify specific PostgreSQL SQLSTATE 23505 via SQLException cause traversal
        boolean found23505 = false;
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SQLException sqlEx) {
                if ("23505".equals(sqlEx.getSQLState())) {
                    found23505 = true;
                    break;
                }
            }
            current = current.getCause();
        }
        assertTrue(found23505, "SQLException cause chain must contain SQLSTATE 23505 (unique_violation)");

        // 4. Verify complete rollback: stock is NOT decremented to 0, remains 1!
        assertEquals(1L, getStock(sku), "Stock must remain 1 after transaction rollback");
        assertEquals(1, getPurchaseCount(sku), "Purchase count must remain 1");
        assertEquals(1L, getSumPurchasedQuantity(sku), "SUM(quantity) must remain 1");
        assertEquals(2L, getStock(sku) + getSumPurchasedQuantity(sku), "Invariant holds after rollback");
    }

    @Test
    @DisplayName("B4. Concurrent load: 20 attempts for 1 unit on stock 5 with 4 workers")
    void fixedService_concurrentLoad_twentyAttemptsOnFiveStock() throws Exception {
        String sku = "OBSIDIAN";
        seedStock(sku, 5L);

        int attempts = 20;
        int poolSize = 4;
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        CountDownLatch readyLatch = new CountDownLatch(poolSize);
        CountDownLatch startGate = new CountDownLatch(1);

        List<Future<PurchaseResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                futures.add(executor.submit(() -> {
                    readyLatch.countDown();
                    boolean opened = startGate.await(5, TimeUnit.SECONDS);
                    if (!opened) {
                        throw new IllegalStateException("startGate timed out in worker");
                    }
                    return fixedService.purchase(UUID.randomUUID(), sku, 1);
                }));
            }

            boolean initialReady = readyLatch.await(5, TimeUnit.SECONDS);
            assertTrue(initialReady, "First pool of workers must be ready before start gate opening");
            startGate.countDown();

            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            int successCount = 0;
            int notAvailableCount = 0;
            for (Future<PurchaseResult> f : futures) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new TimeoutException("Global monotonic deadline expired waiting for load worker future");
                }
                PurchaseResult res = f.get(remainingNanos, TimeUnit.NANOSECONDS);
                if (res == PurchaseResult.SUCCESS) {
                    successCount++;
                } else if (res == PurchaseResult.NOT_AVAILABLE) {
                    notAvailableCount++;
                }
            }

            assertEquals(5, successCount, "Exactly 5 purchases must succeed");
            assertEquals(15, notAvailableCount, "Exactly 15 attempts must be NOT_AVAILABLE");

            long finalStock = getStock(sku);
            long totalQuantity = getSumPurchasedQuantity(sku);
            int purchaseCount = getPurchaseCount(sku);

            assertEquals(0L, finalStock);
            assertEquals(5, purchaseCount);
            assertEquals(5L, totalQuantity);
            assertEquals(5L, finalStock + totalQuantity, "Invariant holds under concurrent load");
        } finally {
            safeShutdownExecutor(executor, startGate, futures);
        }
    }

    @Test
    @DisplayName("B5. Outer Transaction Boundary: outer rollback reverts purchase despite inner SUCCESS")
    void fixedService_outerTransactionRollback_rollsBackPurchase() {
        String sku = "EMERALD_BLOCK";
        seedStock(sku, 5L);

        UUID purchaseId = UUID.randomUUID();
        TransactionTemplate outerTemplate = new TransactionTemplate(transactionManager);
        outerTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outerTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        class TestRollbackException extends RuntimeException {
            TestRollbackException(String msg) { super(msg); }
        }

        assertThrows(TestRollbackException.class, () -> {
            outerTemplate.execute(status -> {
                PurchaseResult res = fixedService.purchase(purchaseId, sku, 2);
                assertEquals(PurchaseResult.SUCCESS, res);

                // Inside outer transaction, stock was decremented to 3
                assertEquals(3L, getStock(sku));
                assertEquals(1, getPurchaseCount(sku));

                // Force outer rollback
                throw new TestRollbackException("Simulated outer business failure");
            });
        });

        // After outer transaction has rolled back:
        // Verify on clean connection that purchase was reverted and stock was restored
        assertEquals(5L, getStock(sku), "Stock must be restored to 5 after outer rollback");
        assertEquals(0, getPurchaseCount(sku), "No purchase record must remain");
        assertEquals(0L, getSumPurchasedQuantity(sku), "SUM(quantity) must be 0");
    }
}
