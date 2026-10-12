package com.aethercore.backend.transaction;

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

import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2-E3A: Declarative Transaction Management with Spring @Transactional and AOP Proxy Semantics.
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

        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "4");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProbeWriter probeWriter;

    @Autowired
    private ProbeOrchestrator probeOrchestrator;

    private static final Map<String, InsertObservation> observations = new ConcurrentHashMap<>();

    record InsertObservation(boolean transactionActive, boolean autoCommit) {}

    public static class BusinessCheckedException extends Exception {
        public BusinessCheckedException(String message) {
            super(message);
        }
    }

    @TestConfiguration
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

        public ProbeWriter(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        void doInsertAndObserve(String caseId, UUID id, String marker) {
            jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
                boolean txActive = TransactionSynchronizationManager.isActualTransactionActive();
                boolean autoCommit = con.getAutoCommit();
                observations.put(caseId, new InsertObservation(txActive, autoCommit));

                String sql = "INSERT INTO market_purchase (id, sku, quantity, created_at) VALUES (?, ?, 1, NOW())";
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
        observations.clear();
        jdbcTemplate.update("DELETE FROM market_purchase");
        jdbcTemplate.update("DELETE FROM market_stock");
        // Seed SKUs used by the probe markers so foreign key REFERENCES market_stock(sku) is satisfied
        for (String sku : java.util.List.of("TX1_MARKER", "TX2_MARKER", "TX3_MARKER", "TX4_MARKER", "TX5_MARKER", "TX6_MARKER", "TX7_MARKER", "TX8_MARKER")) {
            jdbcTemplate.update("INSERT INTO market_stock (sku, available, version) VALUES (?, 100, 0)", sku);
        }
    }

    private int getCommittedRowCount(UUID id) {
        Integer val = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_purchase WHERE id = ?",
                Integer.class,
                id
        );
        return val != null ? val : 0;
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

        InsertObservation obs = observations.get("TX1");
        assertNotNull(obs, "Observation for TX1 must be recorded");
        assertTrue(obs.transactionActive(), "Spring transaction must be active during INSERT");
        assertFalse(obs.autoCommit(), "Auto-commit must be disabled by Spring transaction manager");

        assertEquals(1, getCommittedRowCount(id), "1 row must be committed to database");
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

        InsertObservation obs = observations.get("TX2");
        assertNotNull(obs, "Observation for TX2 must be recorded");
        assertTrue(obs.transactionActive(), "Spring transaction must be active during INSERT");
        assertFalse(obs.autoCommit(), "Auto-commit must be disabled by Spring transaction manager");

        assertEquals(0, getCommittedRowCount(id), "0 rows must be committed; unhandled RuntimeException rolls back");
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

        InsertObservation obs = observations.get("TX3");
        assertNotNull(obs, "Observation for TX3 must be recorded");
        assertFalse(obs.transactionActive(), "Spring transaction must NOT be active (self-invocation bypasses proxy advice)");
        assertTrue(obs.autoCommit(), "Auto-commit must remain enabled on raw connection without Spring transaction");

        assertEquals(1, getCommittedRowCount(id), "1 row committed: statement auto-committed because proxy advice was bypassed");
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

        InsertObservation obs = observations.get("TX4");
        assertNotNull(obs, "Observation for TX4 must be recorded");
        assertTrue(obs.transactionActive(), "Spring transaction must be active in writer bean");
        assertFalse(obs.autoCommit(), "Auto-commit must be disabled by writer\'s Spring transaction");

        assertEquals(0, getCommittedRowCount(id), "0 rows committed: injected bean proxy intercepted the call and rolled back");
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

        InsertObservation obs = observations.get("TX5");
        assertNotNull(obs, "Observation for TX5 must be recorded");
        assertTrue(obs.transactionActive(), "Spring transaction must be active during INSERT");
        assertFalse(obs.autoCommit(), "Auto-commit must be disabled by Spring transaction manager");

        assertEquals(1, getCommittedRowCount(id), "1 row committed: Spring default rollback rule only covers unchecked exceptions");
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

        InsertObservation obs = observations.get("TX6");
        assertNotNull(obs, "Observation for TX6 must be recorded");
        assertTrue(obs.transactionActive(), "Spring transaction must be active during INSERT");
        assertFalse(obs.autoCommit(), "Auto-commit must be disabled by Spring transaction manager");

        assertEquals(0, getCommittedRowCount(id), "0 rows committed: rollbackFor explicitly configured for BusinessCheckedException");
    }

    @Test
    @DisplayName("TX7. In-method catch: RuntimeException caught locally -> transaction commits normally (1 row)")
    void tx7_inMethodCatch_commitsNormally() {
        UUID id = UUID.randomUUID();
        String result = probeWriter.catchRuntimeInsideWrite(id, "TX7_MARKER");
        assertEquals("CAUGHT_LOCALLY", result);

        InsertObservation obs = observations.get("TX7");
        assertNotNull(obs, "Observation for TX7 must be recorded");
        assertTrue(obs.transactionActive(), "Spring transaction must be active during INSERT");
        assertFalse(obs.autoCommit(), "Auto-commit must be disabled by Spring transaction manager");

        assertEquals(1, getCommittedRowCount(id), "1 row committed: exception caught locally did not escape method boundary");
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

        InsertObservation obs = observations.get("TX8");
        assertNotNull(obs, "Observation for TX8 must be recorded");
        assertTrue(obs.transactionActive(), "Spring transaction must be active (inherited from outer transactional method)");
        assertFalse(obs.autoCommit(), "Auto-commit must be disabled by outer Spring transaction");

        assertEquals(0, getCommittedRowCount(id), "0 rows committed: outer transaction intercepted the exception and rolled back");
    }
}
