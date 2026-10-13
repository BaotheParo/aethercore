package com.aethercore.backend.market.idempotency;

import com.aethercore.backend.market.FixedMarketPurchaseService;
import com.aethercore.backend.market.PurchaseResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
public class MarketIdempotencyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aethercore_idempotency_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.sql.init.mode", () -> "always");

        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private FixedMarketPurchaseService fixedPurchaseService;

    @Autowired
    private IdempotentMarketPurchaseService idempotentService;

    private static final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Map<String, Object> observations = new ConcurrentHashMap<>();
    private static final Map<String, Object> runtimeSettings = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM market_idempotency");
        jdbcTemplate.execute("DELETE FROM market_purchase");
        jdbcTemplate.execute("DELETE FROM market_stock");

        if (runtimeSettings.isEmpty()) {
            runtimeSettings.put("serverVersion", postgres.getDockerImageName());
            runtimeSettings.put("databaseName", postgres.getDatabaseName());
            runtimeSettings.put("isolationDefault", "READ COMMITTED");
        }
    }

    private void seedStock(String sku, long available) {
        jdbcTemplate.update(
                "INSERT INTO market_stock (sku, available, version) VALUES (?, ?, 0) " +
                "ON CONFLICT (sku) DO UPDATE SET available = EXCLUDED.available, version = 0",
                sku, available
        );
    }

    private long getStock(String sku) {
        Long val = jdbcTemplate.queryForObject("SELECT available FROM market_stock WHERE sku = ?", Long.class, sku);
        return val != null ? val : 0L;
    }

    private int getPurchaseCount(String sku) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM market_purchase WHERE sku = ?", Integer.class, sku);
        return count != null ? count : 0;
    }

    private long getSumPurchasedQuantity(String sku) {
        Long sum = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM market_purchase WHERE sku = ?", Long.class, sku);
        return sum != null ? sum : 0L;
    }

    private int getIdempotencyCount(UUID callerId, UUID key) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_idempotency WHERE caller_id = ? AND idempotency_key = ?",
                Integer.class, callerId, key
        );
        return count != null ? count : 0;
    }

    private static void recordObservation(String caseId, Map<String, Object> details) {
        observations.put(caseId, details);
    }

    @Test
    @DisplayName("ID1: Sequential replay simulates lost response; second call returns original outcome without re-decrementing stock")
    void id1_sequentialReplayAndLostResponseSimulation() {
        String sku = "POTION_HEAL";
        seedStock(sku, 5L);

        UUID callerId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        IdempotentPurchaseCommand cmd = new IdempotentPurchaseCommand(callerId, key, sku, 2);

        IdempotentPurchaseOutcome outcome1 = idempotentService.purchase(cmd);
        assertNotNull(outcome1);
        assertEquals(PurchaseResult.SUCCESS, outcome1.result());
        assertNotNull(outcome1.purchaseId());
        assertFalse(outcome1.replayed());

        IdempotentMarketPurchaseService serviceB = new IdempotentMarketPurchaseService(
                jdbcTemplate, transactionManager, fixedPurchaseService
        );

        IdempotentPurchaseOutcome outcome2 = serviceB.purchase(cmd);
        assertNotNull(outcome2);
        assertEquals(PurchaseResult.SUCCESS, outcome2.result());
        assertEquals(outcome1.purchaseId(), outcome2.purchaseId(), "Replay must return original purchaseId");
        assertTrue(outcome2.replayed(), "Second call must indicate replayed outcome");
        assertTrue(outcome1.matchesBusinessOutcome(outcome2), "Business outcomes must match identically");

        assertEquals(3L, getStock(sku), "Stock must be 5 - 2 = 3");
        assertEquals(1, getPurchaseCount(sku), "Exactly 1 purchase record created");
        assertEquals(2L, getSumPurchasedQuantity(sku), "Sum quantity must be 2");
        assertEquals(1, getIdempotencyCount(callerId, key), "Exactly 1 idempotency row in COMPLETED state");

        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("caseId", "ID1");
        obs.put("description", "Sequential replay and lost response simulation");
        obs.put("command", Map.of("callerId", callerId.toString(), "idempotencyKey", key.toString(), "sku", sku, "quantity", 2));
        obs.put("expected", Map.of("stock", 3, "purchaseCount", 1, "result", "SUCCESS"));
        obs.put("observed", Map.of(
                "stock", getStock(sku),
                "purchaseCount", getPurchaseCount(sku),
                "purchaseId1", outcome1.purchaseId().toString(),
                "purchaseId2", outcome2.purchaseId().toString(),
                "replayed1", outcome1.replayed(),
                "replayed2", outcome2.replayed()
        ));
        obs.put("assertionsPassed", true);
        recordObservation("ID1", obs);
    }

    @Test
    @DisplayName("ID2: Key reuse with mismatched payload throws IdempotencyConflictException; invalid inputs rejected before claim")
    void id2_keyReuseWithMismatchedPayloadAndInvalidInputs() {
        String sku = "ELIXIR_MANA";
        seedStock(sku, 5L);

        UUID callerId = UUID.randomUUID();
        UUID key = UUID.randomUUID();

        IdempotentPurchaseCommand initialCmd = new IdempotentPurchaseCommand(callerId, key, sku, 1);
        IdempotentPurchaseOutcome initialOutcome = idempotentService.purchase(initialCmd);
        assertEquals(PurchaseResult.SUCCESS, initialOutcome.result());
        assertEquals(4L, getStock(sku));
        assertEquals(1, getPurchaseCount(sku));

        IdempotentPurchaseCommand diffQtyCmd = new IdempotentPurchaseCommand(callerId, key, sku, 2);
        IdempotencyConflictException exQty = assertThrows(
                IdempotencyConflictException.class,
                () -> idempotentService.purchase(diffQtyCmd),
                "Key reuse with different quantity must throw IdempotencyConflictException"
        );
        assertTrue(exQty.getMessage().contains("conflict"), "Exception message must indicate conflict");

        IdempotentPurchaseCommand diffSkuCmd = new IdempotentPurchaseCommand(callerId, key, "DIFFERENT_SKU", 1);
        IdempotencyConflictException exSku = assertThrows(
                IdempotencyConflictException.class,
                () -> idempotentService.purchase(diffSkuCmd),
                "Key reuse with different SKU must throw IdempotencyConflictException"
        );
        assertTrue(exSku.getMessage().contains("conflict"));

        assertEquals(4L, getStock(sku), "Stock must remain 4 after conflict attempts");
        assertEquals(1, getPurchaseCount(sku), "Purchase count must remain 1");
        assertEquals(1, getIdempotencyCount(callerId, key), "Only original idempotency record exists");

        UUID freshKey = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new IdempotentPurchaseCommand(callerId, freshKey, sku, 0));
        assertThrows(IllegalArgumentException.class, () -> new IdempotentPurchaseCommand(callerId, freshKey, sku, -5));
        assertThrows(IllegalArgumentException.class, () -> new IdempotentPurchaseCommand(callerId, freshKey, "   ", 1));
        assertThrows(NullPointerException.class, () -> new IdempotentPurchaseCommand(null, freshKey, sku, 1));
        assertThrows(NullPointerException.class, () -> new IdempotentPurchaseCommand(callerId, null, sku, 1));

        assertEquals(0, getIdempotencyCount(callerId, freshKey), "No idempotency row created for invalid inputs");

        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("caseId", "ID2");
        obs.put("description", "Key reuse with mismatched payload and invalid inputs");
        obs.put("command", Map.of("callerId", callerId.toString(), "idempotencyKey", key.toString()));
        obs.put("expected", Map.of("conflictType", "IdempotencyConflictException", "stockUnchanged", 4));
        obs.put("observed", Map.of(
                "exQtyThrown", exQty.getClass().getSimpleName(),
                "exSkuThrown", exSku.getClass().getSimpleName(),
                "finalStock", getStock(sku),
                "purchaseCount", getPurchaseCount(sku)
        ));
        obs.put("assertionsPassed", true);
        recordObservation("ID2", obs);
    }
    @Test
    @DisplayName("ID3: Concurrent requests with same key wait via DB unique lock, observed by pg_blocking_pids")
    void id3_concurrentRequestsWaitOnDbLock_observedViaPgBlockingPids() throws Exception {
        String sku = "DRAGON_ARMOR";
        seedStock(sku, 5L);

        UUID callerId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        IdempotentPurchaseCommand cmd = new IdempotentPurchaseCommand(callerId, key, sku, 2);

        CountDownLatch aClaimedLatch = new CountDownLatch(1);
        CountDownLatch bStartedLatch = new CountDownLatch(1);
        CountDownLatch aCommitGate = new CountDownLatch(1);

        AtomicReference<Long> aPidRef = new AtomicReference<>();
        AtomicReference<Long> bPidRef = new AtomicReference<>();
        AtomicReference<IdempotentPurchaseOutcome> outcomeARef = new AtomicReference<>();
        AtomicReference<IdempotentPurchaseOutcome> outcomeBRef = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<?>> futures = new ArrayList<>();

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        try {
            Future<?> fA = executor.submit(() -> {
                txTemplate.execute(status -> {
                    Long pid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Long.class);
                    aPidRef.set(pid);

                    IdempotentPurchaseOutcome outcomeA = idempotentService.purchase(cmd);
                    outcomeARef.set(outcomeA);

                    aClaimedLatch.countDown();

                    try {
                        boolean ok = aCommitGate.await(10, TimeUnit.SECONDS);
                        if (!ok) {
                            status.setRollbackOnly();
                            throw new RuntimeException("Worker A timed out waiting for commit gate");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        status.setRollbackOnly();
                        throw new RuntimeException(e);
                    }
                    return null;
                });
            });
            futures.add(fA);

            assertTrue(aClaimedLatch.await(5, TimeUnit.SECONDS), "Worker A must acquire claim");
            Long aPid = aPidRef.get();
            assertNotNull(aPid, "Worker A backend PID must be published");

            Future<?> fB = executor.submit(() -> {
                txTemplate.execute(status -> {
                    Long pid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Long.class);
                    bPidRef.set(pid);
                    bStartedLatch.countDown();

                    IdempotentPurchaseOutcome outcomeB = idempotentService.purchase(cmd);
                    outcomeBRef.set(outcomeB);
                    return null;
                });
            });
            futures.add(fB);

            assertTrue(bStartedLatch.await(5, TimeUnit.SECONDS), "Worker B must start transaction and publish PID");
            Long bPid = bPidRef.get();
            assertNotNull(bPid, "Worker B backend PID must be published");
            assertNotEquals(aPid, bPid, "Worker A and Worker B must operate on distinct PostgreSQL backend PIDs");

            boolean observedBlocked = false;
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            List<Long> capturedBlockingList = new ArrayList<>();

            while (System.nanoTime() < deadlineNanos) {
                Boolean isBlocked = jdbcTemplate.queryForObject(
                        "SELECT ?::int = ANY(pg_blocking_pids(?::int))",
                        Boolean.class,
                        aPid,
                        bPid
                );
                if (Boolean.TRUE.equals(isBlocked)) {
                    observedBlocked = true;
                    capturedBlockingList.add(aPid);
                    break;
                }
                Thread.sleep(50);
            }

            assertTrue(observedBlocked, "pg_blocking_pids must confirm Worker B (PID " + bPid + ") is blocked by Worker A (PID " + aPid + ")");

            aCommitGate.countDown();

            fA.get(5, TimeUnit.SECONDS);
            fB.get(5, TimeUnit.SECONDS);

            IdempotentPurchaseOutcome outA = outcomeARef.get();
            IdempotentPurchaseOutcome outB = outcomeBRef.get();

            assertNotNull(outA);
            assertNotNull(outB);
            assertEquals(PurchaseResult.SUCCESS, outA.result());
            assertEquals(PurchaseResult.SUCCESS, outB.result());
            assertEquals(outA.purchaseId(), outB.purchaseId(), "Worker B must replay Worker A's purchaseId");
            assertFalse(outA.replayed(), "Worker A is the original executor");
            assertTrue(outB.replayed(), "Worker B is the replayed executor");

            assertEquals(3L, getStock(sku), "Stock must only be decremented once (5 - 2 = 3)");
            assertEquals(1, getPurchaseCount(sku), "Exactly 1 purchase record created");
            assertEquals(2L, getSumPurchasedQuantity(sku), "Sum quantity must be 2");
            assertEquals(1, getIdempotencyCount(callerId, key), "Exactly 1 idempotency record");

            Map<String, Object> obs = new LinkedHashMap<>();
            obs.put("caseId", "ID3");
            obs.put("description", "Two requests with identical key wait on DB lock, verified by pg_blocking_pids");
            obs.put("command", Map.of("callerId", callerId.toString(), "idempotencyKey", key.toString()));
            obs.put("expected", Map.of("workerABlockedWorkerB", true, "stock", 3, "purchaseCount", 1));
            obs.put("observed", Map.of(
                    "workerAPid", aPid,
                    "workerBPid", bPid,
                    "observedBlocking", observedBlocked,
                    "blockingPidList", capturedBlockingList,
                    "purchaseIdA", outA.purchaseId().toString(),
                    "purchaseIdB", outB.purchaseId().toString(),
                    "replayedA", outA.replayed(),
                    "replayedB", outB.replayed(),
                    "finalStock", getStock(sku)
            ));
            obs.put("assertionsPassed", true);
            recordObservation("ID3", obs);
        } finally {
            aCommitGate.countDown();
            safeShutdownExecutor(executor, futures);
        }
    }

    @Test
    @DisplayName("ID4: Scope isolation: different caller with same key and same caller with new key are independent")
    void id4_scopeAndNewKeyIsolation() {
        String sku = "BOW_OF_ELVES";
        seedStock(sku, 10L);

        UUID callerA = UUID.randomUUID();
        UUID callerB = UUID.randomUUID();
        UUID key1 = UUID.randomUUID();
        UUID key2 = UUID.randomUUID();

        IdempotentPurchaseCommand cmd1 = new IdempotentPurchaseCommand(callerA, key1, sku, 1);
        IdempotentPurchaseOutcome out1 = idempotentService.purchase(cmd1);
        assertEquals(PurchaseResult.SUCCESS, out1.result());
        assertFalse(out1.replayed());

        IdempotentPurchaseCommand cmd2 = new IdempotentPurchaseCommand(callerB, key1, sku, 1);
        IdempotentPurchaseOutcome out2 = idempotentService.purchase(cmd2);
        assertEquals(PurchaseResult.SUCCESS, out2.result());
        assertFalse(out2.replayed());
        assertNotEquals(out1.purchaseId(), out2.purchaseId(), "Different caller must yield separate purchase");

        IdempotentPurchaseCommand cmd3 = new IdempotentPurchaseCommand(callerA, key2, sku, 1);
        IdempotentPurchaseOutcome out3 = idempotentService.purchase(cmd3);
        assertEquals(PurchaseResult.SUCCESS, out3.result());
        assertFalse(out3.replayed());
        assertNotEquals(out1.purchaseId(), out3.purchaseId());
        assertNotEquals(out2.purchaseId(), out3.purchaseId());

        assertEquals(7L, getStock(sku));
        assertEquals(3, getPurchaseCount(sku));
        assertEquals(3L, getSumPurchasedQuantity(sku));
        assertEquals(1, getIdempotencyCount(callerA, key1));
        assertEquals(1, getIdempotencyCount(callerB, key1));
        assertEquals(1, getIdempotencyCount(callerA, key2));

        IdempotentPurchaseOutcome replay1 = idempotentService.purchase(cmd1);
        IdempotentPurchaseOutcome replay2 = idempotentService.purchase(cmd2);
        IdempotentPurchaseOutcome replay3 = idempotentService.purchase(cmd3);

        assertEquals(out1.purchaseId(), replay1.purchaseId());
        assertEquals(out2.purchaseId(), replay2.purchaseId());
        assertEquals(out3.purchaseId(), replay3.purchaseId());
        assertTrue(replay1.replayed());
        assertTrue(replay2.replayed());
        assertTrue(replay3.replayed());

        assertEquals(7L, getStock(sku), "Stock must remain 7 after replays");
        assertEquals(3, getPurchaseCount(sku), "Purchase count must remain 3");

        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("caseId", "ID4");
        obs.put("description", "Scope and new key isolation across 3 distinct requests");
        obs.put("expected", Map.of("stock", 7, "purchaseCount", 3, "idempotencyCount", 3));
        obs.put("observed", Map.of(
                "stock", getStock(sku),
                "purchaseCount", getPurchaseCount(sku),
                "pId1", out1.purchaseId().toString(),
                "pId2", out2.purchaseId().toString(),
                "pId3", out3.purchaseId().toString()
        ));
        obs.put("assertionsPassed", true);
        recordObservation("ID4", obs);
    }

    @Test
    @DisplayName("ID5: SQL error after stock decrement rolls back claim and allows retry")
    void id5_sqlErrorAfterStockDecrementRollsBackClaimAndAllowsRetry() {
        String sku = "STAFF_OF_FLAMES";
        seedStock(sku, 5L);

        UUID callerId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        IdempotentPurchaseCommand cmd = new IdempotentPurchaseCommand(callerId, key, sku, 2);

        String constraintName = "chk_test_fail_qty_2";
        jdbcTemplate.execute("ALTER TABLE market_purchase ADD CONSTRAINT " + constraintName + " CHECK (quantity != 2)");

        String observedSqlState = null;
        try {
            DataIntegrityViolationException ex = assertThrows(
                    DataIntegrityViolationException.class,
                    () -> idempotentService.purchase(cmd),
                    "Insert into market_purchase with quantity 2 must fail check constraint"
            );

            Throwable root = ex.getRootCause();
            assertNotNull(root, "Root cause must exist");
            assertInstanceOf(SQLException.class, root);
            observedSqlState = ((SQLException) root).getSQLState();
            assertEquals("23514", observedSqlState, "PostgreSQL SQLSTATE for check_violation must be 23514");

            assertEquals(5L, getStock(sku), "Stock must remain 5 after rollback");
            assertEquals(0, getPurchaseCount(sku), "0 purchase records created");
            assertEquals(0, getIdempotencyCount(callerId, key), "Idempotency claim must be rolled back");

        } finally {
            jdbcTemplate.execute("ALTER TABLE market_purchase DROP CONSTRAINT IF EXISTS " + constraintName);
        }

        IdempotentPurchaseOutcome retryOutcome = idempotentService.purchase(cmd);
        assertNotNull(retryOutcome);
        assertEquals(PurchaseResult.SUCCESS, retryOutcome.result());
        assertFalse(retryOutcome.replayed(), "Retry is a fresh execution");

        assertEquals(3L, getStock(sku), "Stock must be 5 - 2 = 3 after retry");
        assertEquals(1, getPurchaseCount(sku), "1 purchase record created after retry");
        assertEquals(1, getIdempotencyCount(callerId, key), "1 completed idempotency record");

        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("caseId", "ID5");
        obs.put("description", "SQL error after stock decrement rolls back claim and allows clean retry");
        obs.put("expected", Map.of("sqlState", "23514", "stockAfterRollback", 5, "stockAfterRetry", 3));
        obs.put("observed", Map.of(
                "observedSqlState", observedSqlState,
                "stockAfterRollback", 5,
                "stockAfterRetry", getStock(sku),
                "purchaseCountAfterRetry", getPurchaseCount(sku)
        ));
        obs.put("assertionsPassed", true);
        recordObservation("ID5", obs);
    }
    @Test
    @DisplayName("ID6: Replay of terminal NOT_AVAILABLE is preserved after restock; new key succeeds")
    void id6_replayTerminalNotAvailableAfterRestock() {
        String sku = "RING_OF_INVISIBILITY";
        seedStock(sku, 1L);

        UUID callerId = UUID.randomUUID();
        UUID key1 = UUID.randomUUID();
        IdempotentPurchaseCommand cmd1 = new IdempotentPurchaseCommand(callerId, key1, sku, 2);

        IdempotentPurchaseOutcome outcome1 = idempotentService.purchase(cmd1);
        assertNotNull(outcome1);
        assertEquals(PurchaseResult.NOT_AVAILABLE, outcome1.result());
        assertNull(outcome1.purchaseId(), "purchaseId must be null for NOT_AVAILABLE");
        assertFalse(outcome1.replayed());

        assertEquals(1L, getStock(sku), "Stock remains 1");
        assertEquals(0, getPurchaseCount(sku), "0 purchases created");
        assertEquals(1, getIdempotencyCount(callerId, key1), "1 idempotency record stored with COMPLETED / NOT_AVAILABLE");

        jdbcTemplate.update("UPDATE market_stock SET available = 5 WHERE sku = ?", sku);
        assertEquals(5L, getStock(sku));

        IdempotentPurchaseOutcome replayOutcome = idempotentService.purchase(cmd1);
        assertNotNull(replayOutcome);
        assertEquals(PurchaseResult.NOT_AVAILABLE, replayOutcome.result(), "Stored terminal outcome must be preserved");
        assertNull(replayOutcome.purchaseId());
        assertTrue(replayOutcome.replayed(), "Replayed flag must be true");

        assertEquals(5L, getStock(sku), "Stock remains 5; replay must not decrement stock");
        assertEquals(0, getPurchaseCount(sku), "Purchase count remains 0");

        UUID key2 = UUID.randomUUID();
        IdempotentPurchaseCommand cmd2 = new IdempotentPurchaseCommand(callerId, key2, sku, 2);
        IdempotentPurchaseOutcome outcome2 = idempotentService.purchase(cmd2);

        assertEquals(PurchaseResult.SUCCESS, outcome2.result());
        assertNotNull(outcome2.purchaseId());
        assertFalse(outcome2.replayed());

        assertEquals(3L, getStock(sku), "Available stock is 5 - 2 = 3");
        assertEquals(1, getPurchaseCount(sku), "1 purchase record");
        assertEquals(2L, getSumPurchasedQuantity(sku), "Sum sold quantity is 2");
        assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM market_idempotency WHERE caller_id = ?", Integer.class, callerId));

        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("caseId", "ID6");
        obs.put("description", "Replay of terminal NOT_AVAILABLE is preserved after restock; new key succeeds");
        obs.put("expected", Map.of("replayOutcome", "NOT_AVAILABLE", "newKeyOutcome", "SUCCESS", "conservationTotal", 5));
        obs.put("observed", Map.of(
                "replayResult", replayOutcome.result().name(),
                "replayFlag", replayOutcome.replayed(),
                "newKeyResult", outcome2.result().name(),
                "finalStock", getStock(sku),
                "totalSold", getSumPurchasedQuantity(sku)
        ));
        obs.put("assertionsPassed", true);
        recordObservation("ID6", obs);
    }

    @Test
    @DisplayName("ID7: Outer transaction rollback rolls back inner idempotency claim; clean retry outside transaction succeeds")
    void id7_outerTransactionRollbackRollsBackClaimAndAllowsRetry() {
        String sku = "BOOTS_OF_SPEED";
        seedStock(sku, 5L);

        UUID callerId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        IdempotentPurchaseCommand cmd = new IdempotentPurchaseCommand(callerId, key, sku, 2);

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        RuntimeException outerException = assertThrows(RuntimeException.class, () -> {
            txTemplate.execute(status -> {
                IdempotentPurchaseOutcome outcome = idempotentService.purchase(cmd);
                assertEquals(PurchaseResult.SUCCESS, outcome.result());
                assertNotNull(outcome.purchaseId());

                throw new RuntimeException("Simulated outer business boundary rollback");
            });
        });
        assertTrue(outerException.getMessage().contains("Simulated outer"));

        assertEquals(5L, getStock(sku), "Stock must remain 5 after outer rollback");
        assertEquals(0, getPurchaseCount(sku), "0 purchase records created");
        assertEquals(0, getIdempotencyCount(callerId, key), "Idempotency record was uncommitted and rolled back");

        IdempotentPurchaseOutcome retryOutcome = idempotentService.purchase(cmd);
        assertNotNull(retryOutcome);
        assertEquals(PurchaseResult.SUCCESS, retryOutcome.result());
        assertNotNull(retryOutcome.purchaseId());
        assertFalse(retryOutcome.replayed());

        assertEquals(3L, getStock(sku), "Stock is 5 - 2 = 3 after retry");
        assertEquals(1, getPurchaseCount(sku), "1 purchase record created after retry");
        assertEquals(1, getIdempotencyCount(callerId, key), "1 completed idempotency record");

        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("caseId", "ID7");
        obs.put("description", "Outer transaction rollback rolls back inner claim and allows retry");
        obs.put("expected", Map.of("stockAfterRollback", 5, "stockAfterRetry", 3, "retrySuccess", true));
        obs.put("observed", Map.of(
                "stockAfterRollback", 5,
                "stockAfterRetry", getStock(sku),
                "purchaseCountAfterRetry", getPurchaseCount(sku)
        ));
        obs.put("assertionsPassed", true);
        recordObservation("ID7", obs);
    }

    @Test
    @DisplayName("Isolation Guard: non-READ_COMMITTED transaction participating in purchase is rejected")
    void isolationGuard_nonReadCommittedRejected() {
        String sku = "GUARD_ITEM";
        seedStock(sku, 5L);

        UUID callerId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        IdempotentPurchaseCommand cmd = new IdempotentPurchaseCommand(callerId, key, sku, 1);

        TransactionTemplate serializableTx = new TransactionTemplate(transactionManager);
        serializableTx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        serializableTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            serializableTx.execute(status -> idempotentService.purchase(cmd));
        });

        assertTrue(ex.getMessage().contains("requires READ_COMMITTED"), "Exception must state READ_COMMITTED requirement");
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

        File file = new File(dir, "idempotency-observations.json");
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
            throw new RuntimeException("Failed to export idempotency observations to " + file.getAbsolutePath(), e);
        }
    }

    private static void safeShutdownExecutor(ExecutorService executor, List<Future<?>> futures) {
        if (futures != null) {
            for (Future<?> f : futures) {
                if (f != null && !f.isDone()) {
                    f.cancel(true);
                }
            }
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }
}