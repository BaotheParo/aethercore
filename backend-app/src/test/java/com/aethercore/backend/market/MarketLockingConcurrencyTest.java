package com.aethercore.backend.market;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2-E1B: Concurrency and Locking Strategy Comparison Integration Test.
 * Compares:
 * 1. Atomic Conditional UPDATE (FixedMarketPurchaseService)
 * 2. Optimistic Concurrency Control (OptimisticMarketPurchaseService)
 * 3. Pessimistic Row Locking (PessimisticMarketPurchaseService)
 *
 * Verifies version increment invariants, conflict rollbacks, retry in fresh transactions,
 * real PostgreSQL pg_blocking_pids() row-lock wait observation without harness deadlock,
 * mixed writers consistency, and outer transaction rollback boundaries.
 */
@SpringBootTest
@Testcontainers
public class MarketLockingConcurrencyTest {

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

        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "4");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET lock_timeout = '5s'; SET statement_timeout = '10s';");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private FixedMarketPurchaseService fixedService;

    @Autowired
    private OptimisticMarketPurchaseService optimisticService;

    @Autowired
    private PessimisticMarketPurchaseService pessimisticService;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM market_purchase");
        jdbcTemplate.update("DELETE FROM market_stock");
    }

    private void seedStock(String sku, long initialAvailable) {
        jdbcTemplate.update("INSERT INTO market_stock (sku, available, version) VALUES (?, ?, 0)", sku, initialAvailable);
    }

    private long getStock(String sku) {
        Long val = jdbcTemplate.queryForObject("SELECT available FROM market_stock WHERE sku = ?", Long.class, sku);
        return val != null ? val : 0L;
    }

    private long getVersion(String sku) {
        Long val = jdbcTemplate.queryForObject("SELECT version FROM market_stock WHERE sku = ?", Long.class, sku);
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
            AssertionError ae = new AssertionError("Cleanup interrupted while waiting for worker termination", e);
            throw ae;
        }
    }

    @Test
    @DisplayName("C1. Optimistic Service: Single purchase decrements stock and increments version from 0 to 1")
    void optimisticService_singlePurchase_decrementsStockAndIncrementsVersion() {
        String sku = "GOLD_INGOT";
        seedStock(sku, 5L);

        PurchaseResult res = optimisticService.purchase(UUID.randomUUID(), sku, 2);

        assertEquals(PurchaseResult.SUCCESS, res);
        assertEquals(3L, getStock(sku));
        assertEquals(1L, getVersion(sku));
        assertEquals(1, getPurchaseCount(sku));
        assertEquals(2L, getSumPurchasedQuantity(sku));
    }

    @Test
    @DisplayName("C2. Optimistic Conflict: Two workers read version 0, one succeeds, one throws StockVersionConflictException with rollback")
    void optimisticService_controlledConflict_oneSucceedsOneRollsBack() throws Exception {
        String sku = "NETHER_STAR";
        seedStock(sku, 1L);

        StockSnapshot snapshot = optimisticService.findSnapshot(sku).orElseThrow();
        assertEquals(0L, snapshot.version());
        assertEquals(1L, snapshot.available());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch worker1Finished = new CountDownLatch(1);
        List<Future<PurchaseResult>> futures = new ArrayList<>();

        try {
            Future<PurchaseResult> future1 = executor.submit(() -> {
                startGate.await(5, TimeUnit.SECONDS);
                try {
                    return optimisticService.purchaseWithExpectedSnapshot(UUID.randomUUID(), snapshot, 1);
                } finally {
                    worker1Finished.countDown();
                }
            });
            futures.add(future1);

            Future<PurchaseResult> future2 = executor.submit(() -> {
                startGate.await(5, TimeUnit.SECONDS);
                boolean w1Done = worker1Finished.await(5, TimeUnit.SECONDS);
                if (!w1Done) {
                    throw new IllegalStateException("worker1 timed out");
                }
                return optimisticService.purchaseWithExpectedSnapshot(UUID.randomUUID(), snapshot, 1);
            });
            futures.add(future2);

            startGate.countDown();

            PurchaseResult r1 = future1.get(5, TimeUnit.SECONDS);
            assertEquals(PurchaseResult.SUCCESS, r1);

            ExecutionException ex = assertThrows(ExecutionException.class, () -> future2.get(5, TimeUnit.SECONDS));
            assertInstanceOf(StockVersionConflictException.class, ex.getCause());
            StockVersionConflictException conflictEx = (StockVersionConflictException) ex.getCause();
            assertEquals(sku, conflictEx.getSku());
            assertEquals(0L, conflictEx.getExpectedVersion());

            assertEquals(0L, getStock(sku));
            assertEquals(1L, getVersion(sku));
            assertEquals(1, getPurchaseCount(sku));
            assertEquals(1L, getSumPurchasedQuantity(sku));
            assertEquals(1L, getStock(sku) + getSumPurchasedQuantity(sku), "Invariant holds after conflict rollback");
        } finally {
            safeShutdownExecutor(executor, startGate, futures);
        }
    }

    @Test
    @DisplayName("C3. Optimistic Retry: Worker encountering conflict retries in fresh transaction and succeeds")
    void optimisticService_retryInFreshTransaction_succeeds() throws Exception {
        String sku = "BEACON";
        seedStock(sku, 2L);

        StockSnapshot initialSnapshot = optimisticService.findSnapshot(sku).orElseThrow();
        assertEquals(0L, initialSnapshot.version());
        assertEquals(2L, initialSnapshot.available());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch worker1Finished = new CountDownLatch(1);
        List<Future<PurchaseResult>> futures = new ArrayList<>();

        try {
            Future<PurchaseResult> future1 = executor.submit(() -> {
                startGate.await(5, TimeUnit.SECONDS);
                try {
                    return optimisticService.purchaseWithExpectedSnapshot(UUID.randomUUID(), initialSnapshot, 1);
                } finally {
                    worker1Finished.countDown();
                }
            });
            futures.add(future1);

            Future<PurchaseResult> future2 = executor.submit(() -> {
                startGate.await(5, TimeUnit.SECONDS);
                worker1Finished.await(5, TimeUnit.SECONDS);

                UUID purchaseId = UUID.randomUUID();
                try {
                    optimisticService.purchaseWithExpectedSnapshot(purchaseId, initialSnapshot, 1);
                    fail("Expected StockVersionConflictException on first attempt");
                    return PurchaseResult.NOT_AVAILABLE;
                } catch (StockVersionConflictException e) {
                    return optimisticService.purchase(purchaseId, sku, 1);
                }
            });
            futures.add(future2);

            startGate.countDown();

            assertEquals(PurchaseResult.SUCCESS, future1.get(5, TimeUnit.SECONDS));
            assertEquals(PurchaseResult.SUCCESS, future2.get(5, TimeUnit.SECONDS));

            assertEquals(0L, getStock(sku));
            assertEquals(2L, getVersion(sku), "Version must be 2 after 2 successful purchases");
            assertEquals(2, getPurchaseCount(sku));
            assertEquals(2L, getSumPurchasedQuantity(sku));
            assertEquals(2L, getStock(sku) + getSumPurchasedQuantity(sku), "Invariant holds after retry");
        } finally {
            safeShutdownExecutor(executor, startGate, futures);
        }
    }

    @Test
    @DisplayName("C4. Pessimistic Lock Wait: T2 blocks on SELECT FOR UPDATE row lock held by T1, verified via pg_blocking_pids()")
    void pessimisticService_lockWait_observedViaPgBlockingPids() throws Exception {
        String sku = "DRAGON_EGG";
        seedStock(sku, 2L);

        CountDownLatch t1LockAcquired = new CountDownLatch(1);
        CountDownLatch t1ReleaseGate = new CountDownLatch(1);
        AtomicReference<Long> t1BackendPidRef = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<PurchaseResult>> futures = new ArrayList<>();

        try {
            Future<PurchaseResult> future1 = executor.submit(() -> {
                return pessimisticService.purchaseWithLockHook(UUID.randomUUID(), sku, 1, lockedSnapshot -> {
                    Long pid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Long.class);
                    t1BackendPidRef.set(pid);
                    t1LockAcquired.countDown();
                    try {
                        boolean opened = t1ReleaseGate.await(5, TimeUnit.SECONDS);
                        if (!opened) {
                            throw new IllegalStateException("t1ReleaseGate timed out waiting for observer verification");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("T1 hook interrupted", e);
                    }
                });
            });
            futures.add(future1);

            assertTrue(t1LockAcquired.await(5, TimeUnit.SECONDS), "T1 must acquire pessimistic lock within 5s");
            Long t1Pid = t1BackendPidRef.get();
            assertNotNull(t1Pid, "T1 backend PID must be captured");

            CountDownLatch t2Started = new CountDownLatch(1);
            Future<PurchaseResult> future2 = executor.submit(() -> {
                t2Started.countDown();
                return pessimisticService.purchase(UUID.randomUUID(), sku, 1);
            });
            futures.add(future2);

            assertTrue(t2Started.await(2, TimeUnit.SECONDS), "T2 thread must have started");

            boolean observedLockWait = false;
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);

            while (System.nanoTime() < deadlineNanos) {
                List<Map<String, Object>> blockedWorkers = jdbcTemplate.queryForList(
                        "SELECT pid, pg_blocking_pids(pid) AS blockers FROM pg_stat_activity " +
                                "WHERE pid != ? AND cardinality(pg_blocking_pids(pid)) > 0",
                        t1Pid
                );

                if (!blockedWorkers.isEmpty()) {
                    for (Map<String, Object> row : blockedWorkers) {
                        Object blockersObj = row.get("blockers");
                        if (blockersObj != null) {
                            String blockersStr = blockersObj.toString();
                            if (blockersStr.contains(String.valueOf(t1Pid))) {
                                observedLockWait = true;
                                break;
                            }
                        }
                    }
                }
                if (observedLockWait) {
                    break;
                }
                Thread.sleep(50);
            }

            assertTrue(observedLockWait, "Must observe T2's backend PID blocked by T1's PID (" + t1Pid + ") via pg_blocking_pids()");

            t1ReleaseGate.countDown();

            PurchaseResult r1 = future1.get(5, TimeUnit.SECONDS);
            PurchaseResult r2 = future2.get(5, TimeUnit.SECONDS);

            assertEquals(PurchaseResult.SUCCESS, r1);
            assertEquals(PurchaseResult.SUCCESS, r2);

            assertEquals(0L, getStock(sku));
            assertEquals(2L, getVersion(sku));
            assertEquals(2, getPurchaseCount(sku));
            assertEquals(2L, getSumPurchasedQuantity(sku));
            assertEquals(2L, getStock(sku) + getSumPurchasedQuantity(sku), "Invariant holds after serialized pessimistic locks");
        } finally {
            safeShutdownExecutor(executor, t1ReleaseGate, futures);
        }
    }

    @Test
    @DisplayName("C5. Mixed Writers Stress: Atomic, Optimistic, and Pessimistic services compete for stock 30")
    void mixedWriters_concurrentCompetition_preservesConsistencyAndVersion() throws Exception {
        String sku = "ANCIENT_DEBRIS";
        int totalUnits = 30;
        seedStock(sku, (long) totalUnits);

        int poolSize = 6;
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<PurchaseResult>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < 10; i++) {
                // Group 1: Atomic conditional update
                futures.add(executor.submit(() -> {
                    startGate.await(5, TimeUnit.SECONDS);
                    return fixedService.purchase(UUID.randomUUID(), sku, 1);
                }));
                // Group 2: Optimistic with retry (using exponential backoff and jitter)
                futures.add(executor.submit(() -> {
                    startGate.await(5, TimeUnit.SECONDS);
                    return optimisticService.purchaseWithRetry(UUID.randomUUID(), sku, 1, 35);
                }));
                // Group 3: Pessimistic SELECT FOR UPDATE
                futures.add(executor.submit(() -> {
                    startGate.await(5, TimeUnit.SECONDS);
                    return pessimisticService.purchase(UUID.randomUUID(), sku, 1);
                }));
            }

            startGate.countDown();

            int successCount = 0;
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            for (Future<PurchaseResult> f : futures) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    throw new TimeoutException("Monotonic deadline exceeded for mixed writers stress test");
                }
                PurchaseResult res = f.get(remaining, TimeUnit.NANOSECONDS);
                if (res == PurchaseResult.SUCCESS) {
                    successCount++;
                }
            }

            assertEquals(totalUnits, successCount, "All 30 units must be purchased successfully");
            assertEquals(0L, getStock(sku), "Stock must be 0");
            assertEquals((long) totalUnits, getVersion(sku), "Version must be exactly incremented 30 times");
            assertEquals(totalUnits, getPurchaseCount(sku), "30 purchase records must exist");
            assertEquals((long) totalUnits, getSumPurchasedQuantity(sku), "Total purchased quantity must equal 30");
            assertEquals((long) totalUnits, getStock(sku) + getSumPurchasedQuantity(sku), "Mixed writers invariant holds");
        } finally {
            safeShutdownExecutor(executor, startGate, futures);
        }
    }

    @Test
    @DisplayName("C6. Optimistic Outer Transaction Rollback: restores stock and version despite inner SUCCESS")
    void optimisticService_outerRollback_restoresStockAndVersion() {
        String sku = "TOTEM";
        seedStock(sku, 5L);

        TransactionTemplate outerTemplate = new TransactionTemplate(transactionManager);
        outerTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outerTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        class OuterRollbackException extends RuntimeException {}

        assertThrows(OuterRollbackException.class, () -> {
            outerTemplate.execute(status -> {
                PurchaseResult res = optimisticService.purchase(UUID.randomUUID(), sku, 2);
                assertEquals(PurchaseResult.SUCCESS, res);
                assertEquals(3L, getStock(sku));
                assertEquals(1L, getVersion(sku));
                throw new OuterRollbackException();
            });
        });

        assertEquals(5L, getStock(sku), "Stock must be restored to 5");
        assertEquals(0L, getVersion(sku), "Version must be restored to 0");
        assertEquals(0, getPurchaseCount(sku));
    }

    @Test
    @DisplayName("C7. Pessimistic Outer Transaction Rollback: restores stock and version despite inner SUCCESS")
    void pessimisticService_outerRollback_restoresStockAndVersion() {
        String sku = "ELYTRA";
        seedStock(sku, 5L);

        TransactionTemplate outerTemplate = new TransactionTemplate(transactionManager);
        outerTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outerTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        class OuterRollbackException extends RuntimeException {}

        assertThrows(OuterRollbackException.class, () -> {
            outerTemplate.execute(status -> {
                PurchaseResult res = pessimisticService.purchase(UUID.randomUUID(), sku, 2);
                assertEquals(PurchaseResult.SUCCESS, res);
                assertEquals(3L, getStock(sku));
                assertEquals(1L, getVersion(sku));
                throw new OuterRollbackException();
            });
        });

        assertEquals(5L, getStock(sku), "Stock must be restored to 5");
        assertEquals(0L, getVersion(sku), "Version must be restored to 0");
        assertEquals(0, getPurchaseCount(sku));
    }

    @Test
    @DisplayName("C8. Pessimistic Service: Insufficient stock returns NOT_AVAILABLE without modifying stock or version")
    void pessimisticService_insufficientStock_returnsNotAvailable() {
        String sku = "TRIDENT";
        seedStock(sku, 1L);

        PurchaseResult res = pessimisticService.purchase(UUID.randomUUID(), sku, 5);

        assertEquals(PurchaseResult.NOT_AVAILABLE, res);
        assertEquals(1L, getStock(sku));
        assertEquals(0L, getVersion(sku));
        assertEquals(0, getPurchaseCount(sku));
    }
}