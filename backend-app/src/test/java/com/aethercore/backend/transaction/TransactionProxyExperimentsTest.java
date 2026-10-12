package com.aethercore.backend.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2-E3A: Declarative Transaction Management with Spring @Transactional and AOP Proxy Semantics.
 *
 * Uses isolated test-only table 'tx_probe' to avoid coupling with business domain entities.
 *
 * Verifies:
 * - AOP Proxy invocation boundaries and interception mechanisms.
 * - Self-invocation pitfall (calling this.method() bypasses proxy advice).
 * - Rollback rules: unchecked (RuntimeException) vs checked (Exception) by default.
 * - rollbackFor attribute overriding default checked exception semantics.
 * - In-method catch behavior (swallowing exceptions preserves commit).
 * - Outer transactional context preservation during self-invocation.
 */
@SpringBootTest
@Testcontainers
@Import(TransactionProxyExperimentsTest.TestProbeConfiguration.class)
public class TransactionProxyExperimentsTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aethercore_tx_proxy_test")
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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        registry.add("spring.datasource.hikari.auto-commit", () -> "true");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProbeWriter probeWriter;

    @Autowired
    private ProbeOrchestrator probeOrchestrator;

    private static final Map<String, CaseObservation> observations = new ConcurrentHashMap<>();

    record InsertObservation(boolean transactionActive, boolean autoCommit) {}

    record CaseObservation(
            String caseId,
            String description,
            String target,
            String invocationBoundary,
            boolean transactionActive,
            boolean autoCommit,
            String callerResult,
            String exceptionThrown,
            int committedRows
    ) {}

    public static class BusinessCheckedException extends Exception {
        public BusinessCheckedException(String message) {
            super(message);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestProbeConfiguration {

        @Bean
        public ProbeWriter probeWriter(JdbcTemplate jdbcTemplate) {
            return new ProbeWriter(jdbcTemplate);
        }

        @Bean
        public ProbeOrchestrator probeOrchestrator(ProbeWriter probeWriter) {
            return new ProbeOrchestrator(probeWriter);
        }
    }

    /**
     * Target Bean 1: ProbeWriter.
     * Contains @Transactional methods for atomic write operations.
     */
    public static class ProbeWriter {
        private final JdbcTemplate jdbcTemplate;
        private final Map<String, InsertObservation> insertObs = new ConcurrentHashMap<>();

        public ProbeWriter(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        InsertObservation getInsertObservation(String caseId) {
            return insertObs.get(caseId);
        }

        void doInsertAndObserve(String caseId, UUID id, String marker) {
            jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
                boolean txActive = TransactionSynchronizationManager.isActualTransactionActive();
                boolean autoCommit = con.getAutoCommit();
                insertObs.put(caseId, new InsertObservation(txActive, autoCommit));

                String sql = "INSERT INTO tx_probe (id, case_id, phase) VALUES (?, ?, 'SINGLE')";
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setObject(1, id);
                    ps.setString(2, marker);
                    ps.executeUpdate();
                }
                return null;
            });
        }

        @Transactional
        public void successfulWrite(UUID id, String marker) {
            doInsertAndObserve("TX1", id, marker);
        }

        @Transactional
        public void runtimeFailureWrite(UUID id, String marker, String caseId) {
            doInsertAndObserve(caseId, id, marker);
            throw new IllegalStateException("Simulated runtime failure in " + caseId);
        }

        @Transactional
        public void checkedFailureDefaultWrite(UUID id, String marker) throws BusinessCheckedException {
            doInsertAndObserve("TX5", id, marker);
            throw new BusinessCheckedException("Simulated checked failure in TX5");
        }

        @Transactional(rollbackFor = BusinessCheckedException.class)
        public void checkedFailureRollbackForWrite(UUID id, String marker) throws BusinessCheckedException {
            doInsertAndObserve("TX6", id, marker);
            throw new BusinessCheckedException("Simulated checked failure in TX6 with rollbackFor");
        }

        @Transactional
        public String catchRuntimeInsideWrite(UUID id, String marker) {
            doInsertAndObserve("TX7", id, marker);
            try {
                throw new RuntimeException("Simulated in-method runtime exception in TX7");
            } catch (RuntimeException ex) {
                // Caught locally - does not escape method boundary to trigger rollback
                return "CAUGHT_LOCALLY";
            }
        }
    }

    /**
     * Target Bean 2: ProbeOrchestrator.
     * Demonstrates self-invocation bypass vs cross-bean proxy delegation.
     * Must NOT have class-level @Transactional.
     */
    public static class ProbeOrchestrator {
        private final ProbeWriter probeWriter;

        public ProbeOrchestrator(ProbeWriter probeWriter) {
            this.probeWriter = probeWriter;
        }

        public void outerNonAnnotatedCallsWriter(UUID id, String marker) {
            probeWriter.runtimeFailureWrite(id, marker, "TX4");
        }

        public void outerNonAnnotatedCallsSelf(UUID id, String marker) {
            this.innerAnnotatedWrite(id, marker, "TX3");
        }

        @Transactional
        public void outerAnnotatedCallsSelf(UUID id, String marker) {
            this.innerAnnotatedWrite(id, marker, "TX8");
        }

        @Transactional
        public void innerAnnotatedWrite(UUID id, String marker, String caseId) {
            probeWriter.doInsertAndObserve(caseId, id, marker);
            throw new IllegalStateException("Simulated failure in innerAnnotatedWrite for " + caseId);
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

    private int getCommittedRowCount(UUID id) {
        Integer val = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tx_probe WHERE id = ?",
                Integer.class,
                id
        );
        return val != null ? val : 0;
    }

    private void recordCase(String caseId, String desc, String target, String boundary,
                            boolean txActive, boolean autoCommit, String callerResult,
                            String exThrown, int committed) {
        observations.put(caseId, new CaseObservation(
                caseId, desc, target, boundary, txActive, autoCommit, callerResult, exThrown, committed
        ));
    }

    @BeforeAll
    static void initObservations() {
        observations.clear();
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

        File file = new File(dir, "proxy-observations.json");
        String runId = System.getProperty("lab.runId", "ad-hoc-" + Instant.now().toEpochMilli());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId);
        payload.put("environment", "Spring Boot 3.3.4 / Spring Framework 6.1.13 / PostgreSQL 16.15");
        payload.put("timestamp", Instant.now().toString());

        List<String> keys = new ArrayList<>(observations.keySet());
        Collections.sort(keys);
        List<CaseObservation> scenarios = new ArrayList<>();
        for (String k : keys) {
            scenarios.add(observations.get(k));
        }
        payload.put("scenarios", scenarios);

        try {
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(file, payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to export proxy observations to " + file.getAbsolutePath(), e);
        }
    }

    @Test
    @DisplayName("Verify Spring AOP Proxying of Test Beans")
    void verifyAopProxying() {
        assertTrue(AopUtils.isAopProxy(probeWriter), "probeWriter must be wrapped in a Spring AOP proxy");
        assertTrue(AopUtils.isCglibProxy(probeWriter), "probeWriter must be a CGLIB class-based proxy");
        assertTrue(AopUtils.isAopProxy(probeOrchestrator), "probeOrchestrator must be wrapped in a Spring AOP proxy");
        assertTrue(AopUtils.isCglibProxy(probeOrchestrator), "probeOrchestrator must be a CGLIB class-based proxy");
    }

    @Test
    @DisplayName("TX1. Outside bean calls annotated proxy method: INSERT then return -> commits 1 row")
    void tx1_outsideBean_callsAnnotatedProxy_successfulCommit() {
        UUID id = UUID.randomUUID();
        probeWriter.successfulWrite(id, "TX1_MARKER");

        InsertObservation obs = probeWriter.getInsertObservation("TX1");
        assertNotNull(obs, "Observation for TX1 must be recorded");
        assertTrue(obs.transactionActive(), "Spring transaction must be active during INSERT");
        assertFalse(obs.autoCommit(), "Auto-commit must be disabled by Spring transaction manager");

        int committed = getCommittedRowCount(id);
        assertEquals(1, committed, "1 row must be committed to database");

        recordCase("TX1", "Outside bean calls annotated proxy method: successful write",
                "ProbeWriter.successfulWrite", "Spring AOP CglibAopProxy -> TransactionInterceptor",
                obs.transactionActive(), obs.autoCommit(), "SUCCESS", null, committed);
    }

    @Test
    @DisplayName("TX2. Outside bean calls annotated proxy method: INSERT then RuntimeException -> rolls back (0 rows)")
    void tx2_outsideBean_callsAnnotatedProxy_runtimeExceptionRollback() {
        UUID id = UUID.randomUUID();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> probeWriter.runtimeFailureWrite(id, "TX2_MARKER", "TX2")
        );
        assertTrue(ex.getMessage().contains("TX2"));

        InsertObservation obs = probeWriter.getInsertObservation("TX2");
        assertNotNull(obs, "Observation for TX2 must be recorded");
        assertTrue(obs.transactionActive(), "Spring transaction must be active during INSERT");
        assertFalse(obs.autoCommit(), "Auto-commit must be disabled by Spring transaction manager");

        int committed = getCommittedRowCount(id);
        assertEquals(0, committed, "0 rows must be committed; unhandled RuntimeException rolls back");

        recordCase("TX2", "Outside bean calls annotated proxy method: unhandled RuntimeException",
                "ProbeWriter.runtimeFailureWrite", "Spring AOP CglibAopProxy -> TransactionInterceptor",
                obs.transactionActive(), obs.autoCommit(), "EXCEPTION", ex.getClass().getName(), committed);
    }

    @Test
    @DisplayName("TX3. Self-invocation bypass: Non-annotated outer calls this.innerAnnotated() -> bypasses proxy, auto-commit commits 1 row")
    void tx3_selfInvocationBypass_nonAnnotatedOuter_commitsDueToAutoCommit() {
        UUID id = UUID.randomUUID();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> probeOrchestrator.outerNonAnnotatedCallsSelf(id, "TX3_MARKER")
        );
        assertTrue(ex.getMessage().contains("TX3"));

        InsertObservation obs = probeWriter.getInsertObservation("TX3");
        assertNotNull(obs, "Observation for TX3 must be recorded");
        assertFalse(obs.transactionActive(), "Spring transaction must NOT be active (self-invocation bypasses proxy advice)");
        assertTrue(obs.autoCommit(), "Auto-commit must remain enabled on raw connection without Spring transaction");

        int committed = getCommittedRowCount(id);
        assertEquals(1, committed, "1 row committed: statement auto-committed because proxy advice was bypassed");

        recordCase("TX3", "Self-invocation bypass: Non-annotated outer calls this.innerAnnotated()",
                "ProbeOrchestrator.outerNonAnnotatedCallsSelf -> this.innerAnnotatedWrite",
                "Direct 'this' heap call (Bypasses Spring AOP Proxy)",
                obs.transactionActive(), obs.autoCommit(), "EXCEPTION", ex.getClass().getName(), committed);
    }

    @Test
    @DisplayName("TX4. Cross-bean call: Non-annotated outer calls injected bean annotated method -> proxy intercepts, rolls back (0 rows)")
    void tx4_crossBeanCall_fromNonAnnotatedOuter_rollsBack() {
        UUID id = UUID.randomUUID();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> probeOrchestrator.outerNonAnnotatedCallsWriter(id, "TX4_MARKER")
        );
        assertTrue(ex.getMessage().contains("TX4"));

        InsertObservation obs = probeWriter.getInsertObservation("TX4");
        assertNotNull(obs, "Observation for TX4 must be recorded");
        assertTrue(obs.transactionActive(), "Spring transaction must be active in writer bean");
        assertFalse(obs.autoCommit(), "Auto-commit must be disabled by writer's Spring transaction");

        int committed = getCommittedRowCount(id);
        assertEquals(0, committed, "0 rows committed: injected bean proxy intercepted the call and rolled back");

        recordCase("TX4", "Cross-bean call from non-annotated outer to injected annotated bean",
                "ProbeOrchestrator.outerNonAnnotatedCallsWriter -> probeWriter.runtimeFailureWrite",
                "Injected Bean Spring AOP Proxy Interception",
                obs.transactionActive(), obs.autoCommit(), "EXCEPTION", ex.getClass().getName(), committed);
    }

    @Test
    @DisplayName("TX5. Checked exception with default @Transactional: INSERT then checked exception -> commits 1 row by default")
    void tx5_checkedExceptionDefault_commitsByDefault() throws Exception {
        UUID id = UUID.randomUUID();

        BusinessCheckedException ex = assertThrows(
                BusinessCheckedException.class,
                () -> probeWriter.checkedFailureDefaultWrite(id, "TX5_MARKER")
        );
        assertTrue(ex.getMessage().contains("TX5"));

        InsertObservation obs = probeWriter.getInsertObservation("TX5");
        assertNotNull(obs, "Observation for TX5 must be recorded");
        assertTrue(obs.transactionActive(), "Spring transaction must be active during INSERT");
        assertFalse(obs.autoCommit(), "Auto-commit must be disabled by Spring transaction manager");

        int committed = getCommittedRowCount(id);
        assertEquals(1, committed, "1 row committed: Spring default rollback rule only covers unchecked exceptions");

        recordCase("TX5", "Checked exception with default @Transactional configuration",
                "ProbeWriter.checkedFailureDefaultWrite", "Spring AOP CglibAopProxy -> TransactionInterceptor",
                obs.transactionActive(), obs.autoCommit(), "EXCEPTION", ex.getClass().getName(), committed);
    }

    @Test
    @DisplayName("TX6. Checked exception with rollbackFor: INSERT then checked exception -> rolls back (0 rows)")
    void tx6_checkedExceptionWithRollbackFor_rollsBack() {
        UUID id = UUID.randomUUID();

        BusinessCheckedException ex = assertThrows(
                BusinessCheckedException.class,
                () -> probeWriter.checkedFailureRollbackForWrite(id, "TX6_MARKER")
        );
        assertTrue(ex.getMessage().contains("TX6"));

        InsertObservation obs = probeWriter.getInsertObservation("TX6");
        assertNotNull(obs, "Observation for TX6 must be recorded");
        assertTrue(obs.transactionActive(), "Spring transaction must be active during INSERT");
        assertFalse(obs.autoCommit(), "Auto-commit must be disabled by Spring transaction manager");

        int committed = getCommittedRowCount(id);
        assertEquals(0, committed, "0 rows committed: rollbackFor explicitly configured for BusinessCheckedException");

        recordCase("TX6", "Checked exception with @Transactional(rollbackFor = BusinessCheckedException.class)",
                "ProbeWriter.checkedFailureRollbackForWrite",
                "Spring AOP CglibAopProxy -> TransactionInterceptor (rollbackFor matched)",
                obs.transactionActive(), obs.autoCommit(), "EXCEPTION", ex.getClass().getName(), committed);
    }

    @Test
    @DisplayName("TX7. In-method catch: RuntimeException caught locally -> transaction commits normally (1 row)")
    void tx7_inMethodCatch_commitsNormally() {
        UUID id = UUID.randomUUID();
        String result = probeWriter.catchRuntimeInsideWrite(id, "TX7_MARKER");
        assertEquals("CAUGHT_LOCALLY", result);

        InsertObservation obs = probeWriter.getInsertObservation("TX7");
        assertNotNull(obs, "Observation for TX7 must be recorded");
        assertTrue(obs.transactionActive(), "Spring transaction must be active during INSERT");
        assertFalse(obs.autoCommit(), "Auto-commit must be disabled by Spring transaction manager");

        int committed = getCommittedRowCount(id);
        assertEquals(1, committed, "1 row committed: exception caught locally did not escape method boundary");

        recordCase("TX7", "In-method catch: RuntimeException swallowed inside method body",
                "ProbeWriter.catchRuntimeInsideWrite",
                "Spring AOP CglibAopProxy -> TransactionInterceptor (Normal return observed)",
                obs.transactionActive(), obs.autoCommit(), "SUCCESS", null, committed);
    }

    @Test
    @DisplayName("TX8. Self-invocation inside annotated outer: Outer TX remains active -> rolls back (0 rows)")
    void tx8_selfInvocationInsideAnnotatedOuter_rollsBackDueToOuterTx() {
        UUID id = UUID.randomUUID();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> probeOrchestrator.outerAnnotatedCallsSelf(id, "TX8_MARKER")
        );
        assertTrue(ex.getMessage().contains("TX8"));

        InsertObservation obs = probeWriter.getInsertObservation("TX8");
        assertNotNull(obs, "Observation for TX8 must be recorded");
        assertTrue(obs.transactionActive(), "Spring transaction must be active (inherited from outer transactional method)");
        assertFalse(obs.autoCommit(), "Auto-commit must be disabled by outer Spring transaction");

        int committed = getCommittedRowCount(id);
        assertEquals(0, committed, "0 rows committed: outer transaction intercepted the exception and rolled back");

        recordCase("TX8", "Self-invocation inside annotated outer: Outer transaction remains active",
                "ProbeOrchestrator.outerAnnotatedCallsSelf -> this.innerAnnotatedWrite",
                "Outer Proxy Transaction intercepts propagating RuntimeException",
                obs.transactionActive(), obs.autoCommit(), "EXCEPTION", ex.getClass().getName(), committed);
    }
}
