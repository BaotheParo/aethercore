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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2-E1B: Concurrency and Locking Strategy Comparison Integration Test (Prompt 10 Refined).
 * Verifies:
 * 1. Controlled Optimistic Conflict across two real purchase() transactions reading distinct snapshots from DB.
 * 2. Optimistic Retry in fresh transaction after previous transaction rolled back.
 * 3. Pessimistic lock wait observed via typed PostgreSQL `pg_blocking_pids()` array query without harness deadlock.
 * 4. Mixed writer invariant: atomic/pessimistic update advances version and invalidates concurrent optimistic snapshot.
 * 5. Rollback on duplicate UUID insertion error restores stock and version.
 * 6. Input validation and insufficient stock contracts.
 * 7. Outer transaction rollback boundaries for both optimistic and pessimistic services.
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
            throw new AssertionError("Cleanup interrupted while waiting for worker termination", e);
        }
    }

    // --- A1 & A2: Non-static Inner Subclasses for Hooking ---

    private class HookedOptimisticService extends OptimisticMarketPurchaseService {
        private final CyclicBarrier barrier;
        private final CountDownLatch w1DoneLatch;
        private final boolean isWorker2;
        final List<Long> pids = Collections.synchronizedList(new ArrayList<>());
        final List<StockSnapshot> snapshots = Collections.synchronizedList(new ArrayList<>());

        HookedOptimisticService(CyclicBarrier barrier, CountDownLatch w1DoneLatch, boolean isWorker2) {
            super(MarketLockingConcurrencyTest.this.jdbcTemplate, MarketLockingConcurrencyTest.this.transactionManager);
            this.barrier = barrier;
            this.w1DoneLatch = w1DoneLatch;
            this.isWorker2 = isWorker2;
        }

        @Override
        protected void onSnapshotRead(StockSnapshot snapshot) {
            Long pid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Long.class);
            pids.add(pid);
            snapshots.add(snapshot);

            if (barrier != null) {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException("Barrier await failed in onSnapshotRead", e);
                }
            }

            if (isWorker2 && w1DoneLatch != null) {
                try {
                    boolean opened = w1DoneLatch.await(5, TimeUnit.SECONDS);
                    if (!opened) {
                        throw new IllegalStateException("w1DoneLatch timed out waiting for worker 1 commit");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Worker 2 interrupted waiting for worker 1", e);
                }
            }
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
    @DisplayName("C2. Optimistic Conflict: Two workers execute purchase(), read version 0, one succeeds, one conflicts and rolls back")
    void optimisticService_controlledConflict_twoRealTransactions_oneSucceedsOneConflicts() throws Exception {
        String sku = "NETHER_STAR";
        seedStock(sku, 1L);

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        CountDownLatch w1DoneLatch = new CountDownLatch(1);

        HookedOptimisticService service1 = new HookedOptimisticService(readBarrier, null, false);
        HookedOptimisticService service2 = new HookedOptimisticService(readBarrier, w1DoneLatch, true);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<PurchaseResult>> futures = new ArrayList<>();

        try {
            Future<PurchaseResult> f1 = executor.submit(() -> {
                boolean gateOpen = startGate.await(5, TimeUnit.SECONDS);
                assertTrue(gateOpen, "startGate must open");
                try {
                    return service1.purchase(UUID.randomUUID(), sku, 1);
                } finally {
                    w1DoneLatch.countDown();
                }
            });
            futures.add(f1);

            Future<PurchaseResult> f2 = executor.submit(() -> {
                boolean gateOpen = startGate.await(5, TimeUnit.SECONDS);
                assertTrue(gateOpen, "startGate must open");
                return service2.purchase(UUID.randomUUID(), sku, 1);
            });
            futures.add(f2);

            startGate.countDown();

            PurchaseResult r1 = f1.get(5, TimeUnit.SECONDS);
            assertEquals(PurchaseResult.SUCCESS, r1);

            ExecutionException ex = assertThrows(ExecutionException.class, () -> f2.get(5, TimeUnit.SECONDS));
            assertInstanceOf(StockVersionConflictException.class, ex.getCause());
            StockVersionConflictException conflictEx = (StockVersionConflictException) ex.getCause();
            assertEquals(sku, conflictEx.getSku());
            assertEquals(0L, conflictEx.getExpectedVersion());

            // Verify both workers executed in distinct backend sessions and read identical snapshot version 0
            assertEquals(1, service1.pids.size());
            assertEquals(1, service2.pids.size());
            assertNotEquals(service1.pids.get(0), service2.pids.get(0), "Workers must run on distinct PostgreSQL backend PIDs");
            assertEquals(0L, service1.snapshots.get(0).version(), "Worker 1 read initial version 0");
            assertEquals(0L, service2.snapshots.get(0).version(), "Worker 2 read stale version 0");

            // Final state: only Worker 1 was committed; Worker 2 rolled back
            assertEquals(0L, getStock(sku));
            assertEquals(1L, getVersion(sku));
            assertEquals(1, getPurchaseCount(sku));
            assertEquals(1L, getSumPurchasedQuantity(sku));
            assertEquals(1L, getStock(sku) + getSumPurchasedQuantity(sku), "Stock invariant preserved after conflict rollback");
        } finally {
            safeShutdownExecutor(executor, startGate, futures);
        }
    }

    @Test
    @DisplayName("C3. Optimistic Retry: Worker encountering conflict retries in a fresh transaction and succeeds")
    void optimisticService_retryInFreshTransaction_succeeds() throws Exception {
        String sku = "BEACON";
        seedStock(sku, 2L);

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        CountDownLatch w1DoneLatch = new CountDownLatch(1);

        HookedOptimisticService service1 = new HookedOptimisticService(readBarrier, null, false);
        HookedOptimisticService service2 = new HookedOptimisticService(readBarrier, w1DoneLatch, true);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<PurchaseResult>> futures = new ArrayList<>();

        try {
            Future<PurchaseResult> f1 = executor.submit(() -> {
                boolean gateOpen = startGate.await(5, TimeUnit.SECONDS);
                assertTrue(gateOpen, "startGate must open");
                try {
                    return service1.purchase(UUID.randomUUID(), sku, 1);
                } finally {
                    w1DoneLatch.countDown();
                }
            });
            futures.add(f1);

            UUID retryPurchaseId = UUID.randomUUID();
            Future<PurchaseResult> f2 = executor.submit(() -> {
                boolean gateOpen = startGate.await(5, TimeUnit.SECONDS);
                assertTrue(gateOpen, "startGate must open");
                try {
                    service2.purchase(retryPurchaseId, sku, 1);
                    fail("Worker 2 must encounter StockVersionConflictException on attempt 1");
                    return PurchaseResult.NOT_AVAILABLE;
                } catch (StockVersionConflictException conflict) {
                    // Driven by caller: retry in a brand-new transaction using standard service
                    return optimisticService.purchase(retryPurchaseId, sku, 1);
                }
            });
            futures.add(f2);

            startGate.countDown();

            assertEquals(PurchaseResult.SUCCESS, f1.get(5, TimeUnit.SECONDS));
            assertEquals(PurchaseResult.SUCCESS, f2.get(5, TimeUnit.SECONDS));

            assertEquals(0L, getStock(sku));
            assertEquals(2L, getVersion(sku), "Version incremented to 2 after two successful purchases");
            assertEquals(2, getPurchaseCount(sku));
            assertEquals(2L, getSumPurchasedQuantity(sku));
            assertEquals(2L, getStock(sku) + getSumPurchasedQuantity(sku), "Invariant holds after retry");
        } finally {
            safeShutdownExecutor(executor, startGate, futures);
        }
    }

    // --- A3: Pessimistic Row Lock Wait & pg_blocking_pids Observation ---

    private class HookedPessimisticService extends PessimisticMarketPurchaseService {
        private final CountDownLatch lockAcquired;
        private final CountDownLatch releaseGate;
        private final AtomicReference<Long> t1PidRef;

        HookedPessimisticService(CountDownLatch lockAcquired, CountDownLatch releaseGate, AtomicReference<Long> t1PidRef) {
            super(MarketLockingConcurrencyTest.this.jdbcTemplate, MarketLockingConcurrencyTest.this.transactionManager);
            this.lockAcquired = lockAcquired;
            this.releaseGate = releaseGate;
            this.t1PidRef = t1PidRef;
        }

        @Override
        protected void onLockAcquired(StockSnapshot locked) {
            Long pid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Long.class);
            t1PidRef.set(pid);
            if (lockAcquired != null) {
                lockAcquired.countDown();
            }
            if (releaseGate != null) {
                try {
                    boolean opened = releaseGate.await(5, TimeUnit.SECONDS);
                    if (!opened) {
                        throw new IllegalStateException("releaseGate timed out in HookedPessimisticService");
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("HookedPessimisticService interrupted", ie);
                }
            }
        }
    }

    @Test
    @DisplayName("C4. Pessimistic Lock Wait on Stock 1: T2 blocks on SELECT FOR UPDATE row lock; pg_blocking_pids verified typed")
    void pessimisticService_lockWait_stockOne_observedViaPgBlockingPids() throws Exception {
        String sku = "DRAGON_EGG";
        seedStock(sku, 1L);

        CountDownLatch t1LockAcquired = new CountDownLatch(1);
        CountDownLatch t1ReleaseGate = new CountDownLatch(1);
        AtomicReference<Long> t1PidRef = new AtomicReference<>();

        HookedPessimisticService hookedService = new HookedPessimisticService(
                t1LockAcquired, t1ReleaseGate, t1PidRef
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<PurchaseResult>> futures = new ArrayList<>();

        try {
            Future<PurchaseResult> f1 = executor.submit(() -> hookedService.purchase(UUID.randomUUID(), sku, 1));
            futures.add(f1);

            assertTrue(t1LockAcquired.await(5, TimeUnit.SECONDS), "T1 must acquire row lock within 5s");
            Long t1Pid = t1PidRef.get();
            assertNotNull(t1Pid, "T1 backend PID must be captured");

            // Worker T2 runs inside an outer REQUIRED transaction in the test harness
            // It explicitly captures and publishes its backend PID before calling purchase()
            AtomicReference<Long> t2PidRef = new AtomicReference<>();
            CountDownLatch t2PidPublished = new CountDownLatch(1);

            TransactionTemplate outerTxTemplate = new TransactionTemplate(transactionManager);
            outerTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

            Future<PurchaseResult> f2 = executor.submit(() -> {
                return outerTxTemplate.execute(status -> {
                    Long pid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Long.class);
                    t2PidRef.set(pid);
                    t2PidPublished.countDown();
                    // Service participates in this outer REQUIRED transaction
                    return pessimisticService.purchase(UUID.randomUUID(), sku, 1);
                });
            });
            futures.add(f2);

            assertTrue(t2PidPublished.await(5, TimeUnit.SECONDS), "T2 must publish its backend PID");
            Long t2Pid = t2PidRef.get();
            assertNotNull(t2Pid, "T2 backend PID must not be null");
            assertNotEquals(t1Pid, t2Pid, "T1 and T2 must operate on distinct database connections/sessions");

            // Query PostgreSQL observer connection with typed SQL: SELECT ?::int = ANY(pg_blocking_pids(?::int))
            // directly verifying that T1 is the blocking PID and T2 is the blocked PID
            boolean observedBlocked = false;
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

            while (System.nanoTime() < deadlineNanos) {
                Boolean isBlocked = jdbcTemplate.queryForObject(
                        "SELECT ?::int = ANY(pg_blocking_pids(?::int))",
                        Boolean.class,
                        t1Pid,
                        t2Pid
                );
                if (Boolean.TRUE.equals(isBlocked)) {
                    observedBlocked = true;
                    break;
                }
                Thread.sleep(50);
            }

            assertTrue(observedBlocked, "pg_blocking_pids must confirm T2 (PID " + t2Pid + ") is blocked directly by T1 (PID " + t1Pid + ")");

            // Release T1 to commit
            t1ReleaseGate.countDown();

            PurchaseResult r1 = f1.get(5, TimeUnit.SECONDS);
            PurchaseResult r2 = f2.get(5, TimeUnit.SECONDS);

            assertEquals(PurchaseResult.SUCCESS, r1, "T1 gets the 1 available unit");
            assertEquals(PurchaseResult.NOT_AVAILABLE, r2, "T2 unblocks after T1 commits and sees 0 units left");

            assertEquals(0L, getStock(sku));
            assertEquals(1L, getVersion(sku));
            assertEquals(1, getPurchaseCount(sku));
            assertEquals(1L, getSumPurchasedQuantity(sku));
            assertEquals(1L, getStock(sku) + getSumPurchasedQuantity(sku), "Invariant holds for serialized pessimistic access");
        } finally {
            safeShutdownExecutor(executor, t1ReleaseGate, futures);
        }
    }

    // --- A4: Mixed Writer Invariant: Proves Writer B increments version ---

    @Test
    @DisplayName("C5. Mixed Writer Invariant: Writer B committing via Atomic UPDATE increments version and causes Worker A to conflict")
    void mixedWriters_atomicUpdateAdvancesVersion_causesOptimisticConflict() throws Exception {
        String sku = "ANCIENT_DEBRIS";
        seedStock(sku, 5L);

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        CountDownLatch bDoneLatch = new CountDownLatch(1);

        HookedOptimisticService serviceA = new HookedOptimisticService(readBarrier, bDoneLatch, true);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<PurchaseResult>> futures = new ArrayList<>();

        try {
            Future<PurchaseResult> futureA = executor.submit(() -> {
                boolean gateOpen = startGate.await(5, TimeUnit.SECONDS);
                assertTrue(gateOpen, "startGate must open");
                return serviceA.purchase(UUID.randomUUID(), sku, 1);
            });
            futures.add(futureA);

            Future<PurchaseResult> futureB = executor.submit(() -> {
                boolean gateOpen = startGate.await(5, TimeUnit.SECONDS);
                assertTrue(gateOpen, "startGate must open");
                try {
                    readBarrier.await(5, TimeUnit.SECONDS);
                    return fixedService.purchase(UUID.randomUUID(), sku, 1);
                } finally {
                    bDoneLatch.countDown();
                }
            });
            futures.add(futureB);

            startGate.countDown();

            PurchaseResult resB = futureB.get(5, TimeUnit.SECONDS);
            assertEquals(PurchaseResult.SUCCESS, resB);

            ExecutionException exA = assertThrows(ExecutionException.class, () -> futureA.get(5, TimeUnit.SECONDS));
            assertInstanceOf(StockVersionConflictException.class, exA.getCause());
            StockVersionConflictException conflict = (StockVersionConflictException) exA.getCause();
            assertEquals(0L, conflict.getExpectedVersion());

            // Database state: only Writer B committed
            assertEquals(4L, getStock(sku));
            assertEquals(1L, getVersion(sku), "Version was incremented by Writer B");
            assertEquals(1, getPurchaseCount(sku));
            assertEquals(1L, getSumPurchasedQuantity(sku));
            assertEquals(5L, getStock(sku) + getSumPurchasedQuantity(sku), "Invariant holds");
        } finally {
            safeShutdownExecutor(executor, startGate, futures);
        }
    }

    // --- A4: Rollback Fault Injection & Input Validation ---

    @Test
    @DisplayName("C6. Optimistic Service: Rollback on duplicate purchase UUID restores stock and version")
    void optimisticService_duplicateUuidRollback_restoresStockAndVersion() {
        String sku = "EMERALD";
        seedStock(sku, 2L);

        UUID fixedId = UUID.randomUUID();

        PurchaseResult r1 = optimisticService.purchase(fixedId, sku, 1);
        assertEquals(PurchaseResult.SUCCESS, r1);
        assertEquals(1L, getStock(sku));
        assertEquals(1L, getVersion(sku));

        DuplicateKeyException ex = assertThrows(DuplicateKeyException.class, () -> {
            optimisticService.purchase(fixedId, sku, 1);
        });

        boolean found23505 = false;
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SQLException sqlEx && "23505".equals(sqlEx.getSQLState())) {
                found23505 = true;
                break;
            }
            current = current.getCause();
        }
        assertTrue(found23505, "Must contain SQLSTATE 23505");

        assertEquals(1L, getStock(sku), "Stock restored to 1");
        assertEquals(1L, getVersion(sku), "Version remains 1");
        assertEquals(1, getPurchaseCount(sku));
        assertEquals(1L, getSumPurchasedQuantity(sku));
        assertEquals(2L, getStock(sku) + getSumPurchasedQuantity(sku), "Invariant preserved");
    }

    @Test
    @DisplayName("C7. Pessimistic Service: Rollback on duplicate purchase UUID restores stock and version")
    void pessimisticService_duplicateUuidRollback_restoresStockAndVersion() {
        String sku = "DIAMOND";
        seedStock(sku, 2L);

        UUID fixedId = UUID.randomUUID();

        PurchaseResult r1 = pessimisticService.purchase(fixedId, sku, 1);
        assertEquals(PurchaseResult.SUCCESS, r1);
        assertEquals(1L, getStock(sku));
        assertEquals(1L, getVersion(sku));

        DuplicateKeyException ex = assertThrows(DuplicateKeyException.class, () -> {
            pessimisticService.purchase(fixedId, sku, 1);
        });

        boolean found23505 = false;
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SQLException sqlEx && "23505".equals(sqlEx.getSQLState())) {
                found23505 = true;
                break;
            }
            current = current.getCause();
        }
        assertTrue(found23505, "Must contain SQLSTATE 23505");

        assertEquals(1L, getStock(sku), "Stock restored to 1");
        assertEquals(1L, getVersion(sku), "Version remains 1");
        assertEquals(1, getPurchaseCount(sku));
        assertEquals(1L, getSumPurchasedQuantity(sku));
        assertEquals(2L, getStock(sku) + getSumPurchasedQuantity(sku), "Invariant preserved");
    }

    @Test
    @DisplayName("C8. Input Validation: Invalid quantity, blank SKU, or null purchaseId rejected with IllegalArgumentException")
    void validation_invalidInputs_rejectedWithoutDbChange() {
        String sku = "LAPIS";
        seedStock(sku, 10L);

        // Optimistic
        assertThrows(IllegalArgumentException.class, () -> optimisticService.purchase(UUID.randomUUID(), sku, 0));
        assertThrows(IllegalArgumentException.class, () -> optimisticService.purchase(UUID.randomUUID(), sku, -1));
        assertThrows(IllegalArgumentException.class, () -> optimisticService.purchase(UUID.randomUUID(), "", 1));
        assertThrows(IllegalArgumentException.class, () -> optimisticService.purchase(null, sku, 1));

        // Pessimistic
        assertThrows(IllegalArgumentException.class, () -> pessimisticService.purchase(UUID.randomUUID(), sku, 0));
        assertThrows(IllegalArgumentException.class, () -> pessimisticService.purchase(UUID.randomUUID(), sku, -1));
        assertThrows(IllegalArgumentException.class, () -> pessimisticService.purchase(UUID.randomUUID(), "  ", 1));
        assertThrows(IllegalArgumentException.class, () -> pessimisticService.purchase(null, sku, 1));

        assertEquals(10L, getStock(sku));
        assertEquals(0L, getVersion(sku));
        assertEquals(0, getPurchaseCount(sku));
    }

    @Test
    @DisplayName("C9. Missing SKU or Insufficient Stock returns NOT_AVAILABLE without modifying DB")
    void missingSkuOrInsufficientStock_returnsNotAvailable() {
        String sku = "REDSTONE";
        seedStock(sku, 2L);

        // Insufficient
        assertEquals(PurchaseResult.NOT_AVAILABLE, optimisticService.purchase(UUID.randomUUID(), sku, 5));
        assertEquals(PurchaseResult.NOT_AVAILABLE, pessimisticService.purchase(UUID.randomUUID(), sku, 5));

        // Missing
        assertEquals(PurchaseResult.NOT_AVAILABLE, optimisticService.purchase(UUID.randomUUID(), "UNKNOWN_SKU", 1));
        assertEquals(PurchaseResult.NOT_AVAILABLE, pessimisticService.purchase(UUID.randomUUID(), "UNKNOWN_SKU", 1));

        assertEquals(2L, getStock(sku));
        assertEquals(0L, getVersion(sku));
        assertEquals(0, getPurchaseCount(sku));
    }

    // --- A4: Outer Transaction Boundary Rollback ---

    @Test
    @DisplayName("C10. Optimistic Outer Transaction Rollback: restores stock and version despite inner SUCCESS")
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
    @DisplayName("C11. Pessimistic Outer Transaction Rollback: restores stock and version despite inner SUCCESS")
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
}
