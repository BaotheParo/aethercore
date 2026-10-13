package com.aethercore.backend.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2-E3B: Transaction Propagation & Rollback-Only Behavior.
 *
 * Verifies the 6 canonical propagation scenarios on a real PostgreSQL 16 Testcontainers instance:
 * - PR1: REQUIRED + REQUIRED (both succeed -> 1 outer row, 1 inner row committed).
 * - PR2: REQUIRED + REQUIRED (inner fails -> inner marks rollback-only -> outer catches but throws UnexpectedRollbackException -> 0 rows).
 * - PR3: REQUIRED + REQUIRES_NEW (inner commits independently -> outer fails -> 0 outer rows, 1 inner row).
 * - PR4: REQUIRED + REQUIRES_NEW (inner fails -> outer catches and commits -> 1 outer row, 0 inner rows).
 * - PR5: REQUIRED + NESTED (inner fails -> rolls back to savepoint -> outer catches and commits -> 1 outer row, 0 inner rows).
 * - PR6: REQUIRED + NESTED (inner succeeds -> savepoint released -> outer fails -> 0 outer rows, 0 inner rows).
 */
@SpringBootTest
@Testcontainers
@Import(TransactionPropagationExperimentsTest.TestPropagationConfig.class)
public class TransactionPropagationExperimentsTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aethercore_tx_prop_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.sql.init.mode", () -> "always");

        registry.add("spring.aop.proxy-target-class", () -> "true");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
        // Maximum pool capacity is 5: accommodates 2 concurrent connections for REQUIRES_NEW suspension plus observer margin
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("spring.datasource.hikari.auto-commit", () -> "true");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PropagationOuterService outerService;

    @Autowired
    private PropagationInnerService innerService;

    public static class InnerBusinessFailure extends RuntimeException {
        public InnerBusinessFailure(String message) {
            super(message);
        }
    }

    public static class OuterBusinessFailure extends RuntimeException {
        public OuterBusinessFailure(String message) {
            super(message);
        }
    }

    record PropagationObservation(
            String caseId,
            String innerPropagation,
            Long outerPid,
            Long innerPid,
            Long resumedOuterPid,
            boolean outerIsNew,
            boolean innerIsNew,
            boolean innerHasSavepoint,
            boolean outerRollbackOnlyInCatch,
            boolean outerBodyReachedReturn,
            String callerResult,
            String exceptionClass,
            int preFailureInnerRows,
            int preFailureOuterRows,
            int finalOuterRows,
            int finalInnerRows
    ) {}

    private static final Map<String, PropagationObservation> observations = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface MidFlightObserver {
        void observe(String caseId) throws Exception;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestPropagationConfig {
        @Bean
        public PropagationInnerService innerService(JdbcTemplate jdbcTemplate) {
            return new PropagationInnerService(jdbcTemplate);
        }

        @Bean
        public PropagationOuterService outerService(JdbcTemplate jdbcTemplate, PropagationInnerService innerService) {
            return new PropagationOuterService(jdbcTemplate, innerService);
        }
    }

    /**
     * Inner Service offering REQUIRED, REQUIRES_NEW, and NESTED transactional methods.
     */
    public static class PropagationInnerService {
        private final JdbcTemplate jdbcTemplate;

        private Long capturedPid;
        private boolean capturedIsNew;
        private boolean capturedHasSavepoint;
        private Boolean capturedAutoCommit;
        private Boolean capturedTxActive;

        public void resetCaptured() {
            capturedPid = null;
            capturedIsNew = false;
            capturedHasSavepoint = false;
            capturedAutoCommit = null;
            capturedTxActive = null;
        }

        public Boolean getCapturedAutoCommit() { return capturedAutoCommit; }
        public Boolean getCapturedTxActive() { return capturedTxActive; }

        public PropagationInnerService(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        public Long getCapturedPid() { return capturedPid; }
        public boolean isCapturedIsNew() { return capturedIsNew; }
        public boolean isCapturedHasSavepoint() { return capturedHasSavepoint; }

        private void recordAndInsert(UUID id, String caseId) {
            capturedIsNew = TransactionAspectSupport.currentTransactionStatus().isNewTransaction();
            capturedHasSavepoint = TransactionAspectSupport.currentTransactionStatus().hasSavepoint();
            capturedTxActive = TransactionSynchronizationManager.isActualTransactionActive();
            capturedPid = jdbcTemplate.execute((ConnectionCallback<Long>) con -> {
                capturedAutoCommit = con.getAutoCommit();
                try (Statement stmt = con.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT pg_backend_pid()")) {
                    rs.next();
                    return rs.getLong(1);
                }
            });

            jdbcTemplate.update(
                    "INSERT INTO tx_probe (id, case_id, phase) VALUES (?, ?, 'INNER')",
                    id, caseId
            );
        }

        @Transactional(propagation = Propagation.REQUIRED)
        public void innerRequiredSuccess(UUID innerId, String caseId) {
            recordAndInsert(innerId, caseId);
        }

        @Transactional(propagation = Propagation.REQUIRED)
        public void innerRequiredFailure(UUID innerId, String caseId) {
            recordAndInsert(innerId, caseId);
            throw new InnerBusinessFailure("Inner REQUIRED failed in " + caseId);
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void innerRequiresNewSuccess(UUID innerId, String caseId) {
            recordAndInsert(innerId, caseId);
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void innerRequiresNewFailure(UUID innerId, String caseId) {
            recordAndInsert(innerId, caseId);
            throw new InnerBusinessFailure("Inner REQUIRES_NEW failed in " + caseId);
        }

        @Transactional(propagation = Propagation.NESTED)
        public void innerNestedSuccess(UUID innerId, String caseId) {
            recordAndInsert(innerId, caseId);
        }

        @Transactional(propagation = Propagation.NESTED)
        public void innerNestedFailure(UUID innerId, String caseId) {
            recordAndInsert(innerId, caseId);
            throw new InnerBusinessFailure("Inner NESTED failed in " + caseId);
        }
    }

    /**
     * Outer Service orchestrating invocations across transactional boundaries.
     */
    public static class PropagationOuterService {
        private final JdbcTemplate jdbcTemplate;
        private final PropagationInnerService innerService;

        private Long capturedOuterPid;
        private Long capturedResumedPid;
        private boolean capturedOuterIsNew;
        private boolean capturedRollbackOnlyInCatch;
        private boolean outerBodyReachedReturn;

        public PropagationOuterService(JdbcTemplate jdbcTemplate, PropagationInnerService innerService) {
            this.jdbcTemplate = jdbcTemplate;
            this.innerService = innerService;
        }

        public Long getCapturedOuterPid() { return capturedOuterPid; }
        public Long getCapturedResumedPid() { return capturedResumedPid; }
        public boolean isCapturedOuterIsNew() { return capturedOuterIsNew; }
        public boolean isCapturedRollbackOnlyInCatch() { return capturedRollbackOnlyInCatch; }
        public boolean isOuterBodyReachedReturn() { return outerBodyReachedReturn; }

        private void recordAndInsertOuter(UUID id, String caseId) {
            capturedOuterIsNew = TransactionAspectSupport.currentTransactionStatus().isNewTransaction();
            capturedOuterPid = jdbcTemplate.execute((ConnectionCallback<Long>) con -> {
                try (Statement stmt = con.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT pg_backend_pid()")) {
                    rs.next();
                    return rs.getLong(1);
                }
            });

            jdbcTemplate.update(
                    "INSERT INTO tx_probe (id, case_id, phase) VALUES (?, ?, 'OUTER')",
                    id, caseId
            );
        }

        private void captureResumedPid() {
            capturedResumedPid = jdbcTemplate.execute((ConnectionCallback<Long>) con -> {
                try (Statement stmt = con.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT pg_backend_pid()")) {
                    rs.next();
                    return rs.getLong(1);
                }
            });
        }

        @Transactional(propagation = Propagation.REQUIRED)
        public void pr1_required_required_success(UUID outerId, UUID innerId, String caseId) {
            recordAndInsertOuter(outerId, caseId);
            innerService.innerRequiredSuccess(innerId, caseId);
            captureResumedPid();
            outerBodyReachedReturn = true;
        }

        @Transactional(propagation = Propagation.REQUIRED)
        public void pr2_required_required_catchRollbackOnly(UUID outerId, UUID innerId, String caseId) {
            recordAndInsertOuter(outerId, caseId);
            try {
                innerService.innerRequiredFailure(innerId, caseId);
            } catch (InnerBusinessFailure ex) {
                // Inner transactional boundary marked the physical transaction as rollback-only
                capturedRollbackOnlyInCatch = TransactionAspectSupport.currentTransactionStatus().isRollbackOnly();
            }
            captureResumedPid();
            // Outer body attempts to return normally; proxy boundary will trigger UnexpectedRollbackException
            outerBodyReachedReturn = true;
        }

        @Transactional(propagation = Propagation.REQUIRED)
        public void pr3_requiresNew_innerCommit_outerFails(UUID outerId, UUID innerId, String caseId, MidFlightObserver observer) throws Exception {
            recordAndInsertOuter(outerId, caseId);
            innerService.innerRequiresNewSuccess(innerId, caseId);
            captureResumedPid();

            // Run independent connection observer while outer transaction is still active
            if (observer != null) {
                observer.observe(caseId);
            }

            // Outer then throws failure
            throw new OuterBusinessFailure("Outer failure in " + caseId + " after inner commit");
        }

        @Transactional(propagation = Propagation.REQUIRED)
        public void pr4_requiresNew_innerFails_outerCatches(UUID outerId, UUID innerId, String caseId) {
            recordAndInsertOuter(outerId, caseId);
            try {
                innerService.innerRequiresNewFailure(innerId, caseId);
            } catch (InnerBusinessFailure ex) {
                capturedRollbackOnlyInCatch = TransactionAspectSupport.currentTransactionStatus().isRollbackOnly();
            }
            captureResumedPid();
            outerBodyReachedReturn = true;
        }

        @Transactional(propagation = Propagation.REQUIRED)
        public void pr5_nested_innerFails_outerCatches(UUID outerId, UUID innerId, String caseId) {
            recordAndInsertOuter(outerId, caseId);
            try {
                innerService.innerNestedFailure(innerId, caseId);
            } catch (InnerBusinessFailure ex) {
                capturedRollbackOnlyInCatch = TransactionAspectSupport.currentTransactionStatus().isRollbackOnly();
            }
            captureResumedPid();
            outerBodyReachedReturn = true;
        }

        @Transactional(propagation = Propagation.REQUIRED)
        public void pr6_nested_innerSuccess_outerFails(UUID outerId, UUID innerId, String caseId) {
            recordAndInsertOuter(outerId, caseId);
            innerService.innerNestedSuccess(innerId, caseId);
            captureResumedPid();
            throw new OuterBusinessFailure("Outer failure in " + caseId + " after inner nested success");
        }
    }

    @BeforeEach
    void setUp() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "Test thread must NOT have active Spring transaction before test execution");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS tx_probe (id UUID PRIMARY KEY, case_id VARCHAR(40) NOT NULL, phase VARCHAR(20) NOT NULL)");
        jdbcTemplate.update("DELETE FROM tx_probe");
    }

    @AfterEach
    void tearDown() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "Test thread must NOT have active Spring transaction after test execution");
    }

    private int getRowCount(String caseId, String phase) {
        Integer val = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tx_probe WHERE case_id = ? AND phase = ?",
                Integer.class,
                caseId, phase
        );
        return val != null ? val : 0;
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

        File file = new File(dir, "propagation-observations.json");
        String runId = System.getProperty("lab.runId", "ad-hoc-" + Instant.now().toEpochMilli());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId);
        String pgVersion;
        try {
            pgVersion = postgres.getContainerInfo().getConfig().getImage();
            pgVersion = "PostgreSQL (image: " + pgVersion + ")";
        } catch (Exception e) {
            pgVersion = "PostgreSQL (configured: postgres:16-alpine)";
        }
        payload.put("environment", "Spring Boot 3.3.4 / Spring Framework 6.1.13 / " + pgVersion);
        payload.put("timestamp", Instant.now().toString());

        List<String> keys = new ArrayList<>(observations.keySet());
        Collections.sort(keys);
        List<PropagationObservation> scenarios = new ArrayList<>();
        for (String k : keys) {
            scenarios.add(observations.get(k));
        }
        payload.put("scenarios", scenarios);

        try {
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(file, payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to export propagation observations to " + file.getAbsolutePath(), e);
        }
    }

    @Test
    @DisplayName("PR1. REQUIRED baseline: Inner participates in outer physical transaction, both commit 1 row")
    void pr1_required_required_success() {
        String caseId = "PR1";
        UUID outerId = UUID.randomUUID();
        UUID innerId = UUID.randomUUID();

        outerService.pr1_required_required_success(outerId, innerId, caseId);

        assertTrue(outerService.isCapturedOuterIsNew(), "Outer transaction must be new physical transaction");
        assertFalse(innerService.isCapturedIsNew(), "Inner REQUIRED must join existing transaction");
        assertFalse(innerService.isCapturedHasSavepoint(), "Inner REQUIRED must not create savepoint");

        assertEquals(outerService.getCapturedOuterPid(), innerService.getCapturedPid(),
                "Outer and Inner REQUIRED must share identical PostgreSQL backend PID");
        assertEquals(outerService.getCapturedOuterPid(), outerService.getCapturedResumedPid(),
                "Outer PID must remain unchanged");

        int finalOuter = getRowCount(caseId, "OUTER");
        int finalInner = getRowCount(caseId, "INNER");

        assertEquals(1, finalOuter, "1 OUTER row committed");
        assertEquals(1, finalInner, "1 INNER row committed");

        observations.put(caseId, new PropagationObservation(
                caseId, "REQUIRED", outerService.getCapturedOuterPid(), innerService.getCapturedPid(),
                outerService.getCapturedResumedPid(), outerService.isCapturedOuterIsNew(),
                innerService.isCapturedIsNew(), innerService.isCapturedHasSavepoint(),
                false, outerService.isOuterBodyReachedReturn(), "SUCCESS", null,
                -1, -1, finalOuter, finalInner
        ));
    }

    @Test
    @DisplayName("PR2. REQUIRED rollback-only: Inner failure marks rollback-only -> outer catch attempts return -> UnexpectedRollbackException (0 rows)")
    void pr2_required_required_catchRollbackOnly() {
        String caseId = "PR2";
        UUID outerId = UUID.randomUUID();
        UUID innerId = UUID.randomUUID();

        UnexpectedRollbackException ex = assertThrows(
                UnexpectedRollbackException.class,
                () -> outerService.pr2_required_required_catchRollbackOnly(outerId, innerId, caseId)
        );

        assertTrue(outerService.isCapturedRollbackOnlyInCatch(),
                "Transaction status in outer catch block must be marked isRollbackOnly = true");
        assertTrue(outerService.isOuterBodyReachedReturn(),
                "Outer body must reach its normal return statement without throwing UnexpectedRollbackException itself");

        assertEquals(outerService.getCapturedOuterPid(), innerService.getCapturedPid(),
                "Outer and Inner must share identical PostgreSQL backend PID");

        int finalOuter = getRowCount(caseId, "OUTER");
        int finalInner = getRowCount(caseId, "INNER");

        assertEquals(0, finalOuter, "0 OUTER rows committed (rolled back)");
        assertEquals(0, finalInner, "0 INNER rows committed (rolled back)");

        observations.put(caseId, new PropagationObservation(
                caseId, "REQUIRED", outerService.getCapturedOuterPid(), innerService.getCapturedPid(),
                outerService.getCapturedResumedPid(), outerService.isCapturedOuterIsNew(),
                innerService.isCapturedIsNew(), innerService.isCapturedHasSavepoint(),
                outerService.isCapturedRollbackOnlyInCatch(), outerService.isOuterBodyReachedReturn(),
                "EXCEPTION", ex.getClass().getName(),
                -1, -1, finalOuter, finalInner
        ));
    }

    @Test
    @DisplayName("PR3. REQUIRES_NEW independent commit: Inner commits on distinct PID -> outer fails -> 0 outer, 1 inner row")
    void pr3_requiresNew_innerCommit_outerFails() {
        String caseId = "PR3";
        UUID outerId = UUID.randomUUID();
        UUID innerId = UUID.randomUUID();

        int[] midFlightCounts = new int[2]; // [inner, outer]

        MidFlightObserver observer = (cid) -> {
            // Independent connection via DriverManager completely outside Spring transaction context
            try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 PreparedStatement psInner = conn.prepareStatement("SELECT COUNT(*) FROM tx_probe WHERE case_id = ? AND phase = 'INNER'");
                 PreparedStatement psOuter = conn.prepareStatement("SELECT COUNT(*) FROM tx_probe WHERE case_id = ? AND phase = 'OUTER'")) {
                psInner.setString(1, cid);
                try (ResultSet rs = psInner.executeQuery()) {
                    rs.next();
                    midFlightCounts[0] = rs.getInt(1);
                }
                psOuter.setString(1, cid);
                try (ResultSet rs = psOuter.executeQuery()) {
                    rs.next();
                    midFlightCounts[1] = rs.getInt(1);
                }
            }
        };

        OuterBusinessFailure ex = assertThrows(
                OuterBusinessFailure.class,
                () -> outerService.pr3_requiresNew_innerCommit_outerFails(outerId, innerId, caseId, observer)
        );

        assertTrue(innerService.isCapturedIsNew(), "Inner REQUIRES_NEW must be a new physical transaction");
        assertFalse(innerService.isCapturedHasSavepoint(), "Inner REQUIRES_NEW must not use savepoint");

        assertNotEquals(outerService.getCapturedOuterPid(), innerService.getCapturedPid(),
                "Outer and Inner REQUIRES_NEW must operate on distinct PostgreSQL backend PIDs");
        assertEquals(outerService.getCapturedOuterPid(), outerService.getCapturedResumedPid(),
                "Outer PID must be restored after inner suspension resumes");

        assertEquals(1, midFlightCounts[0], "Observer while outer active must see INNER committed (=1)");
        assertEquals(0, midFlightCounts[1], "Observer while outer active must NOT see uncommitted OUTER (=0)");

        int finalOuter = getRowCount(caseId, "OUTER");
        int finalInner = getRowCount(caseId, "INNER");

        assertEquals(0, finalOuter, "0 OUTER rows committed (rolled back by OuterBusinessFailure)");
        assertEquals(1, finalInner, "1 INNER row committed (independently committed by REQUIRES_NEW)");

        observations.put(caseId, new PropagationObservation(
                caseId, "REQUIRES_NEW", outerService.getCapturedOuterPid(), innerService.getCapturedPid(),
                outerService.getCapturedResumedPid(), outerService.isCapturedOuterIsNew(),
                innerService.isCapturedIsNew(), innerService.isCapturedHasSavepoint(),
                false, false, "EXCEPTION", ex.getClass().getName(),
                midFlightCounts[0], midFlightCounts[1], finalOuter, finalInner
        ));
    }

    @Test
    @DisplayName("PR4. REQUIRES_NEW inner failure: Inner rolls back on distinct PID -> outer catches and commits (1 outer, 0 inner rows)")
    void pr4_requiresNew_innerFails_outerCatches() {
        String caseId = "PR4";
        UUID outerId = UUID.randomUUID();
        UUID innerId = UUID.randomUUID();

        outerService.pr4_requiresNew_innerFails_outerCatches(outerId, innerId, caseId);

        assertTrue(innerService.isCapturedIsNew(), "Inner REQUIRES_NEW must be new physical transaction");
        assertNotEquals(outerService.getCapturedOuterPid(), innerService.getCapturedPid(),
                "Outer and Inner must operate on distinct PIDs");
        assertEquals(outerService.getCapturedOuterPid(), outerService.getCapturedResumedPid(),
                "Outer PID must be restored");

        assertFalse(outerService.isCapturedRollbackOnlyInCatch(),
                "Outer transaction must NOT be marked rollback-only when inner REQUIRES_NEW rolls back");
        assertTrue(outerService.isOuterBodyReachedReturn(), "Outer must reach return normally");

        int finalOuter = getRowCount(caseId, "OUTER");
        int finalInner = getRowCount(caseId, "INNER");

        assertEquals(1, finalOuter, "1 OUTER row committed");
        assertEquals(0, finalInner, "0 INNER rows committed (REQUIRES_NEW rolled back independently)");

        observations.put(caseId, new PropagationObservation(
                caseId, "REQUIRES_NEW", outerService.getCapturedOuterPid(), innerService.getCapturedPid(),
                outerService.getCapturedResumedPid(), outerService.isCapturedOuterIsNew(),
                innerService.isCapturedIsNew(), innerService.isCapturedHasSavepoint(),
                outerService.isCapturedRollbackOnlyInCatch(), outerService.isOuterBodyReachedReturn(),
                "SUCCESS", null,
                -1, -1, finalOuter, finalInner
        ));
    }

    @Test
    @DisplayName("PR5. NESTED inner failure: Rolls back to savepoint on same PID -> outer catches and commits (1 outer, 0 inner rows)")
    void pr5_nested_innerFails_outerCatches() {
        String caseId = "PR5";
        UUID outerId = UUID.randomUUID();
        UUID innerId = UUID.randomUUID();

        outerService.pr5_nested_innerFails_outerCatches(outerId, innerId, caseId);

        assertFalse(innerService.isCapturedIsNew(), "Inner NESTED is not a new physical transaction");
        assertTrue(innerService.isCapturedHasSavepoint(), "Inner NESTED must create a JDBC/PostgreSQL savepoint");

        assertEquals(outerService.getCapturedOuterPid(), innerService.getCapturedPid(),
                "Outer and Inner NESTED must share identical PostgreSQL backend PID");

        assertFalse(outerService.isCapturedRollbackOnlyInCatch(),
                "Outer transaction must NOT be marked rollback-only when NESTED rolls back to savepoint");
        assertTrue(outerService.isOuterBodyReachedReturn(), "Outer must reach return normally");

        int finalOuter = getRowCount(caseId, "OUTER");
        int finalInner = getRowCount(caseId, "INNER");

        assertEquals(1, finalOuter, "1 OUTER row committed");
        assertEquals(0, finalInner, "0 INNER rows committed (rolled back to savepoint)");

        observations.put(caseId, new PropagationObservation(
                caseId, "NESTED", outerService.getCapturedOuterPid(), innerService.getCapturedPid(),
                outerService.getCapturedResumedPid(), outerService.isCapturedOuterIsNew(),
                innerService.isCapturedIsNew(), innerService.isCapturedHasSavepoint(),
                outerService.isCapturedRollbackOnlyInCatch(), outerService.isOuterBodyReachedReturn(),
                "SUCCESS", null,
                -1, -1, finalOuter, finalInner
        ));
    }

    @Test
    @DisplayName("PR6. NESTED inner success, outer fails: Savepoint release is not physical commit -> outer failure rolls back all (0 rows)")
    void pr6_nested_innerSuccess_outerFails() {
        String caseId = "PR6";
        UUID outerId = UUID.randomUUID();
        UUID innerId = UUID.randomUUID();

        OuterBusinessFailure ex = assertThrows(
                OuterBusinessFailure.class,
                () -> outerService.pr6_nested_innerSuccess_outerFails(outerId, innerId, caseId)
        );

        assertTrue(innerService.isCapturedHasSavepoint(), "Inner NESTED must have savepoint");
        assertEquals(outerService.getCapturedOuterPid(), innerService.getCapturedPid(),
                "Outer and Inner NESTED share identical PID");

        int finalOuter = getRowCount(caseId, "OUTER");
        int finalInner = getRowCount(caseId, "INNER");

        assertEquals(0, finalOuter, "0 OUTER rows committed (rolled back by outer failure)");
        assertEquals(0, finalInner, "0 INNER rows committed (savepoint release is not physical commit; rolled back with outer)");

        observations.put(caseId, new PropagationObservation(
                caseId, "NESTED", outerService.getCapturedOuterPid(), innerService.getCapturedPid(),
                outerService.getCapturedResumedPid(), outerService.isCapturedOuterIsNew(),
                innerService.isCapturedIsNew(), innerService.isCapturedHasSavepoint(),
                false, false, "EXCEPTION", ex.getClass().getName(),
                -1, -1, finalOuter, finalInner
        ));
    }
}
