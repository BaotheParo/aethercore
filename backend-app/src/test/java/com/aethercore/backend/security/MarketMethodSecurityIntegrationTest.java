package com.aethercore.backend.security;

import com.aethercore.backend.market.facade.MarketPurchaseFacade;
import com.aethercore.backend.market.http.PurchaseRequestResultDto;
import com.aethercore.backend.market.idempotency.IdempotentPurchaseOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.convert.converter.Converter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test verifying Method Security on MarketPurchaseFacade (MS1 & MS2).
 * Runs against real PostgreSQL 16 Testcontainers and beans injected from ApplicationContext.
 *
 * Proves that:
 * 1. MS1: Direct Java calls on facade bean are intercepted by Spring Security AOP proxy
 *    - Unauthenticated context -> AuthenticationCredentialsNotFoundException (or AuthenticationException)
 *    - VIEWER role -> AccessDeniedException (before entering method body, DB unchanged)
 *    - TRADER role -> SUCCESS, business logic executes correctly
 * 2. MS2: Owner Scoping at Facade Level
 *    - Caller identity extracted strictly from SecurityContextHolder
 *    - Caller B cannot read Caller A's purchase request
 *    - Caller A and Caller B using same key K have completely isolated records
 */
@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MarketMethodSecurityIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aethercore_method_sec_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MarketPurchaseFacade facade;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NimbusJwtDecoder jwtDecoder;

    @Autowired
    private Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter;

    private static final List<Map<String, Object>> recordedScenarios = new ArrayList<>();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void setSecurityContext(String token) {
        Jwt jwt = jwtDecoder.decode(token);
        AbstractAuthenticationToken authentication = jwtAuthenticationConverter.convert(jwt);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    private void seedStock(String sku, long quantity) {
        jdbcTemplate.update("DELETE FROM market_purchase WHERE sku = ?", sku);
        jdbcTemplate.update("DELETE FROM market_stock WHERE sku = ?", sku);
        jdbcTemplate.update("DELETE FROM market_idempotency WHERE request_sku = ?", sku);
        jdbcTemplate.update(
                "INSERT INTO market_stock (sku, available, version) VALUES (?, ?, 0)",
                sku, quantity
        );
    }

    record DbSnapshot(
            long stockAvailable,
            long stockVersion,
            long purchaseCount,
            long purchaseSumQuantity,
            long idempotencyRowCount
    ) {}

    private DbSnapshot takeSnapshot(String sku) {
        Long available = jdbcTemplate.queryForObject(
                "SELECT available FROM market_stock WHERE sku = ?", Long.class, sku
        );
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM market_stock WHERE sku = ?", Long.class, sku
        );
        Long pCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_purchase WHERE sku = ?", Long.class, sku
        );
        Long pSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM market_purchase WHERE sku = ?", Long.class, sku
        );
        Long iCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_idempotency WHERE request_sku = ?", Long.class, sku
        );

        return new DbSnapshot(
                available != null ? available : 0,
                version != null ? version : 0,
                pCount != null ? pCount : 0,
                pSum != null ? pSum : 0,
                iCount != null ? iCount : 0
        );
    }

    @Test
    @Order(1)
    @DisplayName("MS1: Direct Bean Invocation on Facade is Blocked by Spring AOP Role Gate")
    void ms1_directBeanInvocationMethodSecurityGate() {
        String sku = "MS1_IRON";
        seedStock(sku, 10);
        UUID callerTrader = UUID.randomUUID();
        UUID callerViewer = UUID.randomUUID();
        String tokenTrader = TestJwtSupport.createValidTraderToken(callerTrader);
        String tokenViewer = TestJwtSupport.createToken(callerViewer, List.of("VIEWER"));

        assertThat(AopUtils.isAopProxy(facade)).isTrue();

        List<Map<String, Object>> subcases = new ArrayList<>();

        // Subcase 1: Unauthenticated context -> Denied before body execution
        SecurityContextHolder.clearContext();
        DbSnapshot beforeUnauth = takeSnapshot(sku);
        UUID key1 = UUID.randomUUID();

        String observedExceptionUnauth;
        try {
            facade.purchase(key1, sku, 2);
            throw new AssertionError("Expected authentication exception on unauthenticated facade.purchase");
        } catch (AuthenticationCredentialsNotFoundException | AccessDeniedException ex) {
            observedExceptionUnauth = ex.getClass().getSimpleName();
        }

        DbSnapshot afterUnauth = takeSnapshot(sku);
        assertThat(afterUnauth).isEqualTo(beforeUnauth);

        Map<String, Object> sc1 = new LinkedHashMap<>();
        sc1.put("variant", "direct_call_unauthenticated_denied");
        sc1.put("invocationType", "DIRECT_BEAN");
        sc1.put("role", "ANONYMOUS");
        sc1.put("targetMethod", "purchase");
        sc1.put("observedException", observedExceptionUnauth);
        sc1.put("before", toSnapshotMap(beforeUnauth));
        sc1.put("after", toSnapshotMap(afterUnauth));
        sc1.put("stateUnchanged", beforeUnauth.equals(afterUnauth));
        subcases.add(sc1);

        // Subcase 2: Authenticated with VIEWER role -> AccessDeniedException before body
        setSecurityContext(tokenViewer);
        DbSnapshot beforeViewer = takeSnapshot(sku);
        UUID key2 = UUID.randomUUID();

        assertThatThrownBy(() -> facade.purchase(key2, sku, 2))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> facade.findRequest(key2))
                .isInstanceOf(AccessDeniedException.class);

        DbSnapshot afterViewer = takeSnapshot(sku);
        assertThat(afterViewer).isEqualTo(beforeViewer);

        Map<String, Object> sc2 = new LinkedHashMap<>();
        sc2.put("variant", "direct_call_viewer_role_access_denied");
        sc2.put("invocationType", "DIRECT_BEAN");
        sc2.put("role", "ROLE_VIEWER");
        sc2.put("targetMethod", "purchase");
        sc2.put("observedException", "AccessDeniedException");
        sc2.put("before", toSnapshotMap(beforeViewer));
        sc2.put("after", toSnapshotMap(afterViewer));
        sc2.put("stateUnchanged", beforeViewer.equals(afterViewer));
        subcases.add(sc2);

        // Subcase 3: Positive Control with TRADER role -> Passes proxy advice and executes business logic
        setSecurityContext(tokenTrader);
        DbSnapshot beforeTrader = takeSnapshot(sku);
        UUID key3 = UUID.randomUUID();

        IdempotentPurchaseOutcome outcome = facade.purchase(key3, sku, 2);
        assertThat(outcome.result().name()).isEqualTo("SUCCESS");
        assertThat(outcome.purchaseId()).isNotNull();
        assertThat(outcome.replayed()).isFalse();

        DbSnapshot afterTrader = takeSnapshot(sku);
        assertThat(afterTrader.stockAvailable()).isEqualTo(8);
        assertThat(afterTrader.purchaseCount()).isEqualTo(1);
        assertThat(afterTrader.idempotencyRowCount()).isEqualTo(1);

        Optional<PurchaseRequestResultDto> queryResult = facade.findRequest(key3);
        assertThat(queryResult).isPresent();
        assertThat(queryResult.get().purchaseId()).isEqualTo(outcome.purchaseId());

        Map<String, Object> sc3 = new LinkedHashMap<>();
        sc3.put("variant", "direct_call_trader_role_authorized_success");
        sc3.put("invocationType", "DIRECT_BEAN");
        sc3.put("role", "ROLE_TRADER");
        sc3.put("targetMethod", "purchase");
        sc3.put("outcome", "SUCCESS");
        sc3.put("purchaseId", outcome.purchaseId().toString());
        sc3.put("before", toSnapshotMap(beforeTrader));
        sc3.put("after", toSnapshotMap(afterTrader));
        sc3.put("stateUnchanged", false);
        subcases.add(sc3);

        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("scenarioId", "MS1");
        scenario.put("caseId", "MS1");
        scenario.put("subcaseCount", subcases.size());
        scenario.put("subcases", subcases);
        recordedScenarios.add(scenario);
    }

    @Test
    @Order(2)
    @DisplayName("MS2: Facade Enforces Trusted Caller from SecurityContext & Independent Owner Scoping")
    void ms2_facadeTrustedCallerOwnerScoping() {
        String sku = "MS2_IRON";
        seedStock(sku, 10);
        UUID callerA = UUID.randomUUID();
        UUID callerB = UUID.randomUUID();
        String tokenA = TestJwtSupport.createValidTraderToken(callerA);
        String tokenB = TestJwtSupport.createValidTraderToken(callerB);
        UUID keyK = UUID.randomUUID();

        List<Map<String, Object>> subcases = new ArrayList<>();

        // Step 1: Caller A purchases qty 2 with key K
        setSecurityContext(tokenA);
        DbSnapshot snap0 = takeSnapshot(sku);
        IdempotentPurchaseOutcome outcomeA = facade.purchase(keyK, sku, 2);
        DbSnapshot snap1 = takeSnapshot(sku);
        assertThat(outcomeA.result().name()).isEqualTo("SUCCESS");
        assertThat(outcomeA.replayed()).isFalse();
        assertThat(snap1.stockAvailable()).isEqualTo(8);
        assertThat(snap1.purchaseCount()).isEqualTo(1);

        Map<String, Object> sc1 = new LinkedHashMap<>();
        sc1.put("variant", "caller_a_purchase_key_k");
        sc1.put("callerId", callerA.toString());
        sc1.put("outcome", "SUCCESS");
        sc1.put("purchaseId", outcomeA.purchaseId().toString());
        sc1.put("replayed", false);
        sc1.put("before", toSnapshotMap(snap0));
        sc1.put("after", toSnapshotMap(snap1));
        sc1.put("stateUnchanged", false);
        subcases.add(sc1);

        // Step 2: Caller B queries key K -> must be empty (Owner Scoping in SQL)
        setSecurityContext(tokenB);
        DbSnapshot snapBeforeReadB = takeSnapshot(sku);
        Optional<PurchaseRequestResultDto> readBBefore = facade.findRequest(keyK);
        DbSnapshot snapAfterReadB = takeSnapshot(sku);
        assertThat(readBBefore).isEmpty();
        assertThat(snapAfterReadB).isEqualTo(snapBeforeReadB);

        Map<String, Object> sc2 = new LinkedHashMap<>();
        sc2.put("variant", "caller_b_query_key_k_before_purchase_empty");
        sc2.put("callerId", callerB.toString());
        sc2.put("found", false);
        sc2.put("before", toSnapshotMap(snapBeforeReadB));
        sc2.put("after", toSnapshotMap(snapAfterReadB));
        sc2.put("stateUnchanged", true);
        subcases.add(sc2);

        // Step 3: Caller B purchases same key K and same payload -> fresh purchase with distinct purchaseId
        IdempotentPurchaseOutcome outcomeB = facade.purchase(keyK, sku, 2);
        DbSnapshot snap2 = takeSnapshot(sku);
        assertThat(outcomeB.result().name()).isEqualTo("SUCCESS");
        assertThat(outcomeB.replayed()).isFalse();
        assertThat(outcomeB.purchaseId()).isNotEqualTo(outcomeA.purchaseId());
        assertThat(snap2.stockAvailable()).isEqualTo(6);
        assertThat(snap2.purchaseCount()).isEqualTo(2);
        assertThat(snap2.purchaseSumQuantity()).isEqualTo(4);
        assertThat(snap2.idempotencyRowCount()).isEqualTo(2);

        Map<String, Object> sc3 = new LinkedHashMap<>();
        sc3.put("variant", "caller_b_purchase_same_key_k_distinct_id");
        sc3.put("callerId", callerB.toString());
        sc3.put("outcome", "SUCCESS");
        sc3.put("purchaseId", outcomeB.purchaseId().toString());
        sc3.put("replayed", false);
        sc3.put("before", toSnapshotMap(snapAfterReadB));
        sc3.put("after", toSnapshotMap(snap2));
        sc3.put("stateUnchanged", false);
        subcases.add(sc3);

        // Step 4: Both A and B query key K -> each gets their own distinct purchase
        setSecurityContext(tokenA);
        DbSnapshot snapBeforeReadA = takeSnapshot(sku);
        Optional<PurchaseRequestResultDto> readA = facade.findRequest(keyK);
        DbSnapshot snapAfterReadA = takeSnapshot(sku);
        assertThat(readA).isPresent();
        assertThat(readA.get().purchaseId()).isEqualTo(outcomeA.purchaseId());

        setSecurityContext(tokenB);
        Optional<PurchaseRequestResultDto> readB = facade.findRequest(keyK);
        DbSnapshot snapAfterReadBFinal = takeSnapshot(sku);
        assertThat(readB).isPresent();
        assertThat(readB.get().purchaseId()).isEqualTo(outcomeB.purchaseId());
        assertThat(snapAfterReadBFinal).isEqualTo(snap2);

        Map<String, Object> sc4 = new LinkedHashMap<>();
        sc4.put("variant", "isolated_reads_for_both_callers_same_key");
        sc4.put("callerAPurchaseId", outcomeA.purchaseId().toString());
        sc4.put("callerBPurchaseId", outcomeB.purchaseId().toString());
        sc4.put("before", toSnapshotMap(snapBeforeReadA));
        sc4.put("after", toSnapshotMap(snapAfterReadBFinal));
        sc4.put("stateUnchanged", true);
        subcases.add(sc4);

        // Step 5: Caller A replays key K -> returns existing purchase A, replayed=true, DB unchanged
        setSecurityContext(tokenA);
        DbSnapshot snapBeforeReplay = takeSnapshot(sku);
        IdempotentPurchaseOutcome replayA = facade.purchase(keyK, sku, 2);
        DbSnapshot snapAfterReplay = takeSnapshot(sku);
        assertThat(replayA.result().name()).isEqualTo("SUCCESS");
        assertThat(replayA.purchaseId()).isEqualTo(outcomeA.purchaseId());
        assertThat(replayA.replayed()).isTrue();
        assertThat(snapAfterReplay).isEqualTo(snapBeforeReplay);

        Map<String, Object> sc5 = new LinkedHashMap<>();
        sc5.put("variant", "caller_a_replay_same_key_cached_outcome");
        sc5.put("callerId", callerA.toString());
        sc5.put("outcome", "SUCCESS");
        sc5.put("purchaseId", replayA.purchaseId().toString());
        sc5.put("replayed", true);
        sc5.put("before", toSnapshotMap(snapBeforeReplay));
        sc5.put("after", toSnapshotMap(snapAfterReplay));
        sc5.put("stateUnchanged", true);
        subcases.add(sc5);

        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("scenarioId", "MS2");
        scenario.put("caseId", "MS2");
        scenario.put("subcaseCount", subcases.size());
        scenario.put("subcases", subcases);
        recordedScenarios.add(scenario);
    }

    private Map<String, Object> toSnapshotMap(DbSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stockAvailable", (int) s.stockAvailable());
        m.put("stockVersion", (int) s.stockVersion());
        m.put("purchaseCount", (int) s.purchaseCount());
        m.put("purchaseSumQuantity", (int) s.purchaseSumQuantity());
        m.put("idempotencyRowCount", (int) s.idempotencyRowCount());
        return m;
    }

    @AfterAll
    static void emitEvidence() {
        String evidenceDir = System.getProperty("lab.evidenceDir");
        if (evidenceDir == null || evidenceDir.trim().isEmpty()) {
            return;
        }

        File targetDir = new File(evidenceDir);
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }

        String runId = System.getProperty("lab.runId", "adhoc-run");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1.0.0");
        payload.put("runId", runId);
        payload.put("generatedAt", Instant.now().toString());
        payload.put("scenarioCount", recordedScenarios.size());
        payload.put("scenarios", recordedScenarios);

        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        File outFile = new File(targetDir, "method-security-observations.json");
        try {
            om.writeValue(outFile, payload);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write method-security-observations.json", e);
        }
    }
}
