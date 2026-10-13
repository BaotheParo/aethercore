package com.aethercore.backend.locking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
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
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2-E2A: PostgreSQL Deadlock, Lock Ordering, and Whole-Transaction Retry Experiments.
 *
 * Scenarios:
 * - DL1: Controlled reproduction of a 2-row deadlock cycle between two concurrent workers.
 *        Verifies SQLSTATE 40P01 on victim, survivor commit, and clean rollback of victim's earlier writes.
 * - DL2: Consistent ascending ID lock ordering [1, 2] eliminates wait-for cycles.
 *        Verifies real row lock contention via pg_blocking_pids before release and successful commit of both.
 * - DL3: Whole-transaction retry of victim outside the failed transaction boundary.
 *        Verifies fresh txid_current(), re-execution of full logical operation, and final invariant restoration.
 */
@SpringBootTest
@Testcontainers
public class PostgresDeadlockExperimentsTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aethercore_deadlock_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.sql.init.mode", () -> "always");

        // Pool capacity 5: accommodates 2 concurrent workers + 1 observer + margin
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "2");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    public record WorkerObservation(
            String workerId,
            String operationId,
            int attempt,
            Long pid,
            Long txId,
            List<Integer> updateOrder,
            boolean firstUpdateCompleted,
            String outcome,
            String sqlState,
            String exceptionClass
    ) {}

    public record DeadlockCaseObservation(
            String caseId,
            String description,
            int totalAttempts,
            int committedOperations,
            int deadlockVictims,
            long counterA,
            long counterB,
            int operationMarkers,
            List<WorkerObservation> workers
    ) {}

    private static final Map<String, DeadlockCaseObservation> observations = new ConcurrentHashMap<>();

    record WorkerResult(
            String name,
            UUID opId,
            int attempt,
            Long pid,
            Long txId,
            List<Integer> order,
            boolean committed,
            boolean deadlock,
            String sqlState,
            String exceptionClass
    ) {}

    @BeforeAll
    static void initObservations() {
        observations.clear();
    }

    @BeforeEach
    void setUp() {
        // Initialize probe tables if not exists
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS deadlock_counter (id INTEGER PRIMARY KEY, value BIGINT NOT NULL)"
        );
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS deadlock_operation (operation_id UUID PRIMARY KEY)"
        );

        // Reset state
        jdbcTemplate.execute("DELETE FROM deadlock_operation");
        jdbcTemplate.execute("DELETE FROM deadlock_counter");
        jdbcTemplate.execute("INSERT INTO deadlock_counter (id, value) VALUES (1, 0), (2, 0)");
    }

    private void configureSessionTimeouts(Connection con) throws SQLException {
        try (Statement stmt = con.createStatement()) {
            stmt.execute("SET deadlock_timeout = '1s'");
            stmt.execute("SET lock_timeout = '10s'");
            stmt.execute("SET statement_timeout = '15s'");
        }
    }

    private static String extractSqlState(Throwable ex) {
        Throwable curr = ex;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (curr != null && visited.add(curr)) {
            if (curr instanceof SQLException se) {
                if (se.getSQLState() != null) {
                    return se.getSQLState();
                }
                SQLException next = se.getNextException();
                while (next != null && visited.add(next)) {
                    if (next.getSQLState() != null) {
                        return next.getSQLState();
                    }
                    next = next.getNextException();
                }
            }
            curr = curr.getCause();
        }
        return null;
    }

    private WorkerResult executeWorker(String name, UUID opId, int attempt, List<Integer> order,
                                       CyclicBarrier barrier, AtomicReference<WorkerObservation> obsRef) {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        AtomicLong pidRef = new AtomicLong();
        AtomicLong txIdRef = new AtomicLong();
        AtomicBoolean firstDone = new AtomicBoolean(false);

        try {
            tt.execute(status -> {
                assertTrue(status.isNewTransaction(), "Each worker attempt must begin in a new physical transaction");
                jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
                    configureSessionTimeouts(con);
                    return null;
                });

                Long pid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Long.class);
                Long txid = jdbcTemplate.queryForObject("SELECT txid_current()", Long.class);
                pidRef.set(pid != null ? pid : -1L);
                txIdRef.set(txid != null ? txid : -1L);

                // 1. Insert marker
                jdbcTemplate.update("INSERT INTO deadlock_operation (operation_id) VALUES (?)", opId);

                // 2. First update
                int firstId = order.get(0);
                jdbcTemplate.update("UPDATE deadlock_counter SET value = value + 1 WHERE id = ?", firstId);
                firstDone.set(true);

                // 3. Coordinate via barrier if provided
                if (barrier != null) {
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Barrier interrupted", e);
                    } catch (Exception e) {
                        throw new RuntimeException("Barrier coordination failure", e);
                    }
                }

                // 4. Second update (conflicting row lock)
                int secondId = order.get(1);
                jdbcTemplate.update("UPDATE deadlock_counter SET value = value + 1 WHERE id = ?", secondId);

                return true;
            });

            WorkerResult res = new WorkerResult(name, opId, attempt, pidRef.get(), txIdRef.get(), order, true, false, null, null);
            obsRef.set(new WorkerObservation(name, opId.toString(), attempt, pidRef.get(), txIdRef.get(), order, firstDone.get(), "COMMITTED", null, null));
            return res;

        } catch (Exception ex) {
            String sqlState = extractSqlState(ex);
            boolean isDeadlock = "40P01".equals(sqlState);
            WorkerResult res = new WorkerResult(name, opId, attempt, pidRef.get(), txIdRef.get(), order, false, isDeadlock, sqlState, ex.getClass().getName());
            obsRef.set(new WorkerObservation(name, opId.toString(), attempt, pidRef.get(), txIdRef.get(), order, firstDone.get(), isDeadlock ? "DEADLOCK_VICTIM" : "FAILED", sqlState, ex.getClass().getName()));
            return res;
        }
    }

    private void cleanupExecutor(ExecutorService executor, Future<?>... futures) {
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
                    throw new IllegalStateException("ExecutorService did not terminate after shutdownNow");
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during executor termination", e);
        }
    }

    @Test
    @DisplayName("DL1: Controlled Deadlock Reproduction between Two Concurrent Workers")
    void testDL1_ControlledDeadlock() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<WorkerResult> f1 = null;
        Future<WorkerResult> f2 = null;
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            UUID op1 = UUID.randomUUID();
            UUID op2 = UUID.randomUUID();

            AtomicReference<WorkerObservation> obs1 = new AtomicReference<>();
            AtomicReference<WorkerObservation> obs2 = new AtomicReference<>();

            Callable<WorkerResult> worker1 = () -> executeWorker("T1", op1, 1, List.of(1, 2), barrier, obs1);
            Callable<WorkerResult> worker2 = () -> executeWorker("T2", op2, 1, List.of(2, 1), barrier, obs2);

            f1 = executor.submit(worker1);
            f2 = executor.submit(worker2);

            WorkerResult r1 = f1.get(30, TimeUnit.SECONDS);
            WorkerResult r2 = f2.get(30, TimeUnit.SECONDS);

            int commits = (r1.committed ? 1 : 0) + (r2.committed ? 1 : 0);
            int deadlocks = (r1.deadlock ? 1 : 0) + (r2.deadlock ? 1 : 0);

            assertEquals(1, commits, "Exactly one transaction must survive and commit");
            assertEquals(1, deadlocks, "Exactly one transaction must fail with SQLSTATE 40P01 (deadlock detected)");
            assertNotEquals(r1.pid, r2.pid, "Workers must run on distinct PostgreSQL backend connections");

            long valA = jdbcTemplate.queryForObject("SELECT value FROM deadlock_counter WHERE id = 1", Long.class);
            long valB = jdbcTemplate.queryForObject("SELECT value FROM deadlock_counter WHERE id = 2", Long.class);
            int markerCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM deadlock_operation", Integer.class);

            // Invariant: value(A) == value(B) == committed operations (1)
            assertEquals(1L, valA, "Counter A must equal 1");
            assertEquals(1L, valB, "Counter B must equal 1");
            assertEquals(1, markerCount, "Marker count must equal 1");

            UUID survivorOp = r1.committed ? op1 : op2;
            UUID victimOp = r1.committed ? op2 : op1;
            assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM deadlock_operation WHERE operation_id = ?", Integer.class, survivorOp));
            assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM deadlock_operation WHERE operation_id = ?", Integer.class, victimOp),
                    "Victim's marker must be rolled back despite being inserted before the barrier");

            observations.put("DL1", new DeadlockCaseObservation(
                    "DL1",
                    "Controlled reproduction of 2-row deadlock cycle between two workers",
                    2, commits, deadlocks, valA, valB, markerCount,
                    List.of(obs1.get(), obs2.get())
            ));
        } finally {
            cleanupExecutor(executor, f1, f2);
        }
    }

    @Test
    @DisplayName("DL2: Consistent Lock Ordering Eliminates Wait-for Cycle")
    void testDL2_ConsistentLockOrdering() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch t1HoldGate = new CountDownLatch(1);
        Future<WorkerResult> f1 = null;
        Future<WorkerResult> f2 = null;
        try {
            UUID op1 = UUID.randomUUID();
            UUID op2 = UUID.randomUUID();

            CountDownLatch t1HasA = new CountDownLatch(1);
            CountDownLatch t2Started = new CountDownLatch(1);

            AtomicLong t1Pid = new AtomicLong();
            AtomicLong t2Pid = new AtomicLong();
            AtomicLong t1TxId = new AtomicLong();
            AtomicLong t2TxId = new AtomicLong();

            AtomicReference<WorkerObservation> obs1 = new AtomicReference<>();
            AtomicReference<WorkerObservation> obs2 = new AtomicReference<>();

            Callable<WorkerResult> worker1 = () -> {
                TransactionTemplate tt = new TransactionTemplate(transactionManager);
                tt.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

                try {
                    tt.execute(status -> {
                        jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
                            configureSessionTimeouts(con);
                            return null;
                        });
                        long pid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Long.class);
                        long txid = jdbcTemplate.queryForObject("SELECT txid_current()", Long.class);
                        t1Pid.set(pid);
                        t1TxId.set(txid);

                        jdbcTemplate.update("INSERT INTO deadlock_operation (operation_id) VALUES (?)", op1);
                        // Update A (id 1)
                        jdbcTemplate.update("UPDATE deadlock_counter SET value = value + 1 WHERE id = 1");
                        t1HasA.countDown();

                        // Hold lock on A until observer confirms contention
                        try {
                            if (!t1HoldGate.await(10, TimeUnit.SECONDS)) {
                                throw new RuntimeException("T1 hold gate timed out");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("T1 interrupted", e);
                        }

                        // Update B (id 2)
                        jdbcTemplate.update("UPDATE deadlock_counter SET value = value + 1 WHERE id = 2");
                        return true;
                    });
                    obs1.set(new WorkerObservation("T1", op1.toString(), 1, t1Pid.get(), t1TxId.get(), List.of(1, 2), true, "COMMITTED", null, null));
                    return new WorkerResult("T1", op1, 1, t1Pid.get(), t1TxId.get(), List.of(1, 2), true, false, null, null);
                } catch (Exception ex) {
                    obs1.set(new WorkerObservation("T1", op1.toString(), 1, t1Pid.get(), t1TxId.get(), List.of(1, 2), false, "FAILED", extractSqlState(ex), ex.getClass().getName()));
                    return new WorkerResult("T1", op1, 1, t1Pid.get(), t1TxId.get(), List.of(1, 2), false, false, extractSqlState(ex), ex.getClass().getName());
                }
            };

            Callable<WorkerResult> worker2 = () -> {
                // Wait until T1 holds row lock on A
                if (!t1HasA.await(10, TimeUnit.SECONDS)) {
                    throw new RuntimeException("Wait for T1 timed out");
                }
                TransactionTemplate tt = new TransactionTemplate(transactionManager);
                tt.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

                try {
                    tt.execute(status -> {
                        jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
                            configureSessionTimeouts(con);
                            return null;
                        });
                        long pid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Long.class);
                        long txid = jdbcTemplate.queryForObject("SELECT txid_current()", Long.class);
                        t2Pid.set(pid);
                        t2TxId.set(txid);
                        t2Started.countDown();

                        jdbcTemplate.update("INSERT INTO deadlock_operation (operation_id) VALUES (?)", op2);
                        // Also updates in order [1, 2] -> Will block waiting for A
                        jdbcTemplate.update("UPDATE deadlock_counter SET value = value + 1 WHERE id = 1");
                        jdbcTemplate.update("UPDATE deadlock_counter SET value = value + 1 WHERE id = 2");
                        return true;
                    });
                    obs2.set(new WorkerObservation("T2", op2.toString(), 1, t2Pid.get(), t2TxId.get(), List.of(1, 2), true, "COMMITTED", null, null));
                    return new WorkerResult("T2", op2, 1, t2Pid.get(), t2TxId.get(), List.of(1, 2), true, false, null, null);
                } catch (Exception ex) {
                    obs2.set(new WorkerObservation("T2", op2.toString(), 1, t2Pid.get(), t2TxId.get(), List.of(1, 2), false, "FAILED", extractSqlState(ex), ex.getClass().getName()));
                    return new WorkerResult("T2", op2, 1, t2Pid.get(), t2TxId.get(), List.of(1, 2), false, false, extractSqlState(ex), ex.getClass().getName());
                }
            };

            f1 = executor.submit(worker1);
            f2 = executor.submit(worker2);

            assertTrue(t2Started.await(10, TimeUnit.SECONDS), "T2 must start before contention verification");

            // Verify via pg_blocking_pids that T2 is blocked by T1
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean verifiedBlocked = false;
            while (System.nanoTime() < deadline) {
                Boolean isBlocked = jdbcTemplate.queryForObject(
                        "SELECT (?::int = ANY(pg_blocking_pids(?::int)))",
                        Boolean.class,
                        (int) t1Pid.get(), (int) t2Pid.get()
                );
                if (Boolean.TRUE.equals(isBlocked)) {
                    verifiedBlocked = true;
                    break;
                }
                Thread.sleep(50);
            }
            assertTrue(verifiedBlocked, "Observer must verify T2 (PID " + t2Pid.get() + ") is blocked by T1 (PID " + t1Pid.get() + ")");

            // Release T1 to commit and allow T2 to unblock
            t1HoldGate.countDown();

            WorkerResult r1 = f1.get(20, TimeUnit.SECONDS);
            WorkerResult r2 = f2.get(20, TimeUnit.SECONDS);

            assertTrue(r1.committed, "T1 must commit successfully");
            assertTrue(r2.committed, "T2 must commit successfully after unblocking");
            assertFalse(r1.deadlock, "No deadlock in T1");
            assertFalse(r2.deadlock, "No deadlock in T2");

            long valA = jdbcTemplate.queryForObject("SELECT value FROM deadlock_counter WHERE id = 1", Long.class);
            long valB = jdbcTemplate.queryForObject("SELECT value FROM deadlock_counter WHERE id = 2", Long.class);
            int markerCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM deadlock_operation", Integer.class);

            assertEquals(2L, valA);
            assertEquals(2L, valB);
            assertEquals(2, markerCount);

            observations.put("DL2", new DeadlockCaseObservation(
                    "DL2",
                    "Consistent ascending ID lock ordering [1, 2] avoids deadlock under real contention",
                    2, 2, 0, valA, valB, markerCount,
                    List.of(obs1.get(), obs2.get())
            ));
        } finally {
            t1HoldGate.countDown();
            cleanupExecutor(executor, f1, f2);
        }
    }

    @Test
    @DisplayName("DL3: Whole-Transaction Retry of Deadlock Victim")
    void testDL3_WholeTransactionRetry() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<WorkerResult> f1 = null;
        Future<WorkerResult> f2 = null;
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            UUID op1 = UUID.randomUUID();
            UUID op2 = UUID.randomUUID();

            AtomicReference<WorkerObservation> obs1 = new AtomicReference<>();
            AtomicReference<WorkerObservation> obs2 = new AtomicReference<>();

            Callable<WorkerResult> worker1 = () -> executeWorker("T1", op1, 1, List.of(1, 2), barrier, obs1);
            Callable<WorkerResult> worker2 = () -> executeWorker("T2", op2, 1, List.of(2, 1), barrier, obs2);

            f1 = executor.submit(worker1);
            f2 = executor.submit(worker2);

            WorkerResult r1 = f1.get(30, TimeUnit.SECONDS);
            WorkerResult r2 = f2.get(30, TimeUnit.SECONDS);

            WorkerResult victimResult = r1.deadlock ? r1 : r2;
            WorkerResult survivorResult = r1.committed ? r1 : r2;

            assertTrue(victimResult.deadlock, "Victim must fail with deadlock");
            assertEquals("40P01", victimResult.sqlState, "SQLSTATE must be 40P01");
            assertTrue(survivorResult.committed, "Survivor must commit");

            // Verify phase 1 state
            assertEquals(1L, jdbcTemplate.queryForObject("SELECT value FROM deadlock_counter WHERE id = 1", Long.class));
            assertEquals(1L, jdbcTemplate.queryForObject("SELECT value FROM deadlock_counter WHERE id = 2", Long.class));
            assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM deadlock_operation", Integer.class));

            // Phase 2: Whole-transaction retry of victim
            AtomicLong retryPid = new AtomicLong();
            AtomicLong retryTxId = new AtomicLong();
            AtomicReference<WorkerObservation> obsRetry = new AtomicReference<>();

            TransactionTemplate retryTt = new TransactionTemplate(transactionManager);
            retryTt.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            retryTt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            retryTt.execute(status -> {
                assertTrue(status.isNewTransaction(), "Retry attempt must begin in a fresh physical transaction");
                jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
                    configureSessionTimeouts(con);
                    return null;
                });
                long pid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Long.class);
                long txid = jdbcTemplate.queryForObject("SELECT txid_current()", Long.class);
                retryPid.set(pid);
                retryTxId.set(txid);

                // Re-execute entire logical operation for victim opId
                jdbcTemplate.update("INSERT INTO deadlock_operation (operation_id) VALUES (?)", victimResult.opId);
                // Normalized order [1, 2]
                jdbcTemplate.update("UPDATE deadlock_counter SET value = value + 1 WHERE id = 1");
                jdbcTemplate.update("UPDATE deadlock_counter SET value = value + 1 WHERE id = 2");
                return true;
            });

            assertNotEquals(victimResult.txId, retryTxId.get(),
                    "Retry must run in a distinct transaction (txid_current) from the failed attempt");

            obsRetry.set(new WorkerObservation(
                    victimResult.name + "-retry",
                    victimResult.opId.toString(),
                    2,
                    retryPid.get(),
                    retryTxId.get(),
                    List.of(1, 2),
                    true,
                    "COMMITTED",
                    null,
                    null
            ));

            // Final invariant assertions
            long finalA = jdbcTemplate.queryForObject("SELECT value FROM deadlock_counter WHERE id = 1", Long.class);
            long finalB = jdbcTemplate.queryForObject("SELECT value FROM deadlock_counter WHERE id = 2", Long.class);
            int finalMarkers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM deadlock_operation", Integer.class);

            assertEquals(2L, finalA, "Counter A must equal 2 after retry");
            assertEquals(2L, finalB, "Counter B must equal 2 after retry");
            assertEquals(2, finalMarkers, "Both operation markers must be committed");

            assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM deadlock_operation WHERE operation_id = ?", Integer.class, survivorResult.opId));
            assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM deadlock_operation WHERE operation_id = ?", Integer.class, victimResult.opId));

            observations.put("DL3", new DeadlockCaseObservation(
                    "DL3",
                    "Whole-transaction retry of victim outside failed transaction boundary recovers invariants",
                    3, 2, 1, finalA, finalB, finalMarkers,
                    List.of(
                            r1.deadlock ? obs1.get() : obs2.get(),
                            r1.committed ? obs1.get() : obs2.get(),
                            obsRetry.get()
                    )
            ));
        } finally {
            cleanupExecutor(executor, f1, f2);
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

        File file = new File(dir, "deadlock-observations.json");
        String runId = System.getProperty("lab.runId", "ad-hoc-" + Instant.now().toEpochMilli());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId);
        String pgVersion;
        try {
            pgVersion = postgres.getContainerInfo().getConfig().getImage();
            // Also append server version if available
            pgVersion = "PostgreSQL (image: " + pgVersion + ")";
        } catch (Exception e) {
            pgVersion = "PostgreSQL (configured: postgres:16-alpine)";
        }
        payload.put("environment", "Spring Boot 3.3.4 / Spring Framework 6.1.13 / " + pgVersion);
        payload.put("timestamp", Instant.now().toString());

        List<String> keys = new ArrayList<>(observations.keySet());
        Collections.sort(keys);
        List<DeadlockCaseObservation> scenarios = new ArrayList<>();
        for (String k : keys) {
            scenarios.add(observations.get(k));
        }
        payload.put("scenarios", scenarios);

        try {
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(file, payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to export deadlock observations to " + file.getAbsolutePath(), e);
        }
    }
}
