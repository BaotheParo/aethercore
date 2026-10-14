package com.aethercore.backend.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full HTTP and database integration test suite for Module M2-E8A.
 * Proves the full acceptance matrix SEC1 through SEC9 against real PostgreSQL and Spring Security:
 * - Real RS256 token verification using ephemeral RSA key pairs.
 * - Database snapshots verifying zero writes on rejections.
 * - Owner scoping and isolation between callers A and B.
 * - Replay semantics and conflict detection.
 * - Generates security-observations.json evidence.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MarketSecurityIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aethercore_security_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("aethercore.security.jwt.issuer", () -> TestJwtSupport.DEFAULT_ISSUER);
        registry.add("aethercore.security.jwt.audience", () -> TestJwtSupport.DEFAULT_AUDIENCE);
        registry.add("aethercore.security.jwt.public-key-pem", () -> TestJwtSupport.TEST_PUBLIC_KEY_PEM);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final List<Map<String, Object>> OBSERVATIONS = new ArrayList<>();

    private void seedStock(String sku, int quantity) {
        jdbcTemplate.update(
                "INSERT INTO market_stock (sku, available, version) VALUES (?, ?, 0) " +
                "ON CONFLICT (sku) DO UPDATE SET available = EXCLUDED.available, version = 0",
                sku, quantity
        );
    }

    private record DbSnapshot(
            int stockAvailable,
            long stockVersion,
            int purchaseCount,
            int purchaseSumQuantity,
            int idempotencyRowCount
    ) {}

    private DbSnapshot takeSnapshot(String sku) {
        Integer available = jdbcTemplate.queryForObject(
                "SELECT available FROM market_stock WHERE sku = ?", Integer.class, sku
        );
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM market_stock WHERE sku = ?", Long.class, sku
        );
        Integer purchases = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM market_purchase WHERE sku = ?", Integer.class, sku
        );
        Integer sumQty = jdbcTemplate.queryForObject(
                "SELECT coalesce(sum(quantity), 0) FROM market_purchase WHERE sku = ?", Integer.class, sku
        );
        Integer idempRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM market_idempotency WHERE request_sku = ?", Integer.class, sku
        );

        return new DbSnapshot(
                available != null ? available : 0,
                version != null ? version : 0L,
                purchases != null ? purchases : 0,
                sumQty != null ? sumQty : 0,
                idempRows != null ? idempRows : 0
        );
    }

    // =========================================================================
    // SEC1: Unauthenticated / Untrusted Key / Malformed Token
    // =========================================================================
    @Test
    @Order(1)
    @DisplayName("SEC1: Missing token, malformed token, and wrong signing key return 401 with Bearer challenge; no DB writes")
    void sec1_unauthenticatedOrUntrustedRejection() throws Exception {
        String sku = "SEC1_IRON";
        seedStock(sku, 10);
        UUID callerId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        String validBody = "{\"sku\":\"" + sku + "\",\"quantity\":2}";

        List<Map<String, Object>> subcases = new ArrayList<>();

        // Subcase 1: Missing Token
        DbSnapshot beforeMissing = takeSnapshot(sku);
        MvcResult resMissing = mockMvc.perform(post("/api/market/purchases")
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE))
                .andReturn();
        DbSnapshot afterMissing = takeSnapshot(sku);
        assertThat(afterMissing).isEqualTo(beforeMissing);
        subcases.add(recordSubcase("missing_token", 401, resMissing, beforeMissing, afterMissing));

        // Subcase 2: Malformed Token
        DbSnapshot beforeMalformed = takeSnapshot(sku);
        MvcResult resMalformed = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.valid.jwt.payload")
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE))
                .andReturn();
        DbSnapshot afterMalformed = takeSnapshot(sku);
        assertThat(afterMalformed).isEqualTo(beforeMalformed);
        subcases.add(recordSubcase("malformed_token", 401, resMalformed, beforeMalformed, afterMalformed));

        // Subcase 3: Token signed with Untrusted Key B
        String untrustedToken = TestJwtSupport.createTokenWithWrongKey(callerId, List.of("TRADER"));
        DbSnapshot beforeUntrusted = takeSnapshot(sku);
        MvcResult resUntrusted = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + untrustedToken)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE))
                .andReturn();
        DbSnapshot afterUntrusted = takeSnapshot(sku);
        assertThat(afterUntrusted).isEqualTo(beforeUntrusted);
        subcases.add(recordSubcase("wrong_key_b", 401, resUntrusted, beforeUntrusted, afterUntrusted));

        recordScenario("SEC1", subcases);
    }

    // =========================================================================
    // SEC2: Claim Validation Failure Variants (Issuer, Audience, Exp, Sub, Roles)
    // =========================================================================
    @Test
    @Order(2)
    @DisplayName("SEC2: Right key but invalid claims (iss, aud, exp, nbf, sub, roles) return 401; no DB writes; no 500")
    void sec2_invalidClaimsRejection() throws Exception {
        String sku = "SEC2_IRON";
        seedStock(sku, 10);
        UUID callerId = UUID.randomUUID();
        String validBody = "{\"sku\":\"" + sku + "\",\"quantity\":2}";

        List<Map<String, Object>> subcases = new ArrayList<>();

        Map<String, String> tokenVariants = new LinkedHashMap<>();
        tokenVariants.put("wrong_issuer", TestJwtSupport.createTokenWithWrongIssuer(callerId, List.of("TRADER"), "https://evil.issuer.com"));
        tokenVariants.put("missing_audience", TestJwtSupport.createTokenWithoutAudience(callerId, List.of("TRADER")));
        tokenVariants.put("wrong_audience", TestJwtSupport.createTokenWithWrongAudience(callerId, List.of("TRADER"), "wrong-app"));
        tokenVariants.put("expired", TestJwtSupport.createExpiredToken(callerId, List.of("TRADER")));
        tokenVariants.put("future_nbf", TestJwtSupport.createFutureNbfToken(callerId, List.of("TRADER")));
        tokenVariants.put("missing_exp", TestJwtSupport.createTokenWithoutExp(callerId, List.of("TRADER")));
        tokenVariants.put("missing_sub", TestJwtSupport.createTokenWithoutSub(List.of("TRADER")));
        tokenVariants.put("invalid_sub_non_uuid", TestJwtSupport.createTokenWithInvalidSub("not-a-valid-uuid", List.of("TRADER")));
        tokenVariants.put("roles_as_integer", TestJwtSupport.createTokenWithRolesPayload(callerId, 12345));
        tokenVariants.put("roles_as_string", TestJwtSupport.createTokenWithRolesPayload(callerId, "TRADER"));
        tokenVariants.put("sub_with_whitespace", TestJwtSupport.createTokenWithSubWhitespace(callerId, List.of("TRADER")));
        tokenVariants.put("sub_shortened_uuid", TestJwtSupport.createTokenWithShortenedSub(callerId, List.of("TRADER")));
        tokenVariants.put("roles_array_non_string_element", TestJwtSupport.createTokenWithNonStringRoleElement(callerId));

        for (Map.Entry<String, String> entry : tokenVariants.entrySet()) {
            String variantName = entry.getKey();
            String token = entry.getValue();
            UUID key = UUID.randomUUID();

            DbSnapshot before = takeSnapshot(sku);
            MvcResult result = mockMvc.perform(post("/api/market/purchases")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .header("Idempotency-Key", key.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validBody))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE))
                    .andReturn();

            DbSnapshot after = takeSnapshot(sku);
            assertThat(after).isEqualTo(before);
            subcases.add(recordSubcase(variantName, 401, result, before, after));
        }

        recordScenario("SEC2", subcases);
    }

    // =========================================================================
    // SEC3: Role Authorization (401 vs 403 vs 200)
    // =========================================================================
    @Test
    @Order(3)
    @DisplayName("SEC3: Missing role or VIEWER role returns 403; TRADER role returns 200; demonstrates 401 vs 403")
    void sec3_roleAuthorizationDistinction() throws Exception {
        String sku = "SEC3_IRON";
        seedStock(sku, 10);
        String validBody = "{\"sku\":\"" + sku + "\",\"quantity\":2}";

        List<Map<String, Object>> subcases = new ArrayList<>();

        // Subcase 1: Missing/Empty roles -> 403
        UUID callerEmpty = UUID.randomUUID();
        String tokenEmpty = TestJwtSupport.createToken(callerEmpty, List.of());
        DbSnapshot beforeEmpty = takeSnapshot(sku);
        MvcResult resEmpty = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenEmpty)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isForbidden())
                .andReturn();
        DbSnapshot afterEmpty = takeSnapshot(sku);
        assertThat(afterEmpty).isEqualTo(beforeEmpty);
        subcases.add(recordSubcase("empty_roles", 403, resEmpty, beforeEmpty, afterEmpty));

        // Subcase 2: VIEWER role only -> 403
        UUID callerViewer = UUID.randomUUID();
        String tokenViewer = TestJwtSupport.createToken(callerViewer, List.of("VIEWER"));
        DbSnapshot beforeViewer = takeSnapshot(sku);
        MvcResult resViewer = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenViewer)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isForbidden())
                .andReturn();
        DbSnapshot afterViewer = takeSnapshot(sku);
        assertThat(afterViewer).isEqualTo(beforeViewer);
        subcases.add(recordSubcase("viewer_role_only", 403, resViewer, beforeViewer, afterViewer));

        // Subcase 3: Positive Control with TRADER role -> 200
        UUID callerTrader = UUID.randomUUID();
        String tokenTrader = TestJwtSupport.createToken(callerTrader, List.of("TRADER"));
        DbSnapshot beforeTrader = takeSnapshot(sku);
        MvcResult resTrader = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenTrader)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isOk())
                .andReturn();
        DbSnapshot afterTrader = takeSnapshot(sku);
        assertThat(afterTrader.stockAvailable()).isEqualTo(beforeTrader.stockAvailable() - 2);
        assertThat(afterTrader.purchaseCount()).isEqualTo(beforeTrader.purchaseCount() + 1);
        subcases.add(recordSubcase("trader_role_success", 200, resTrader, beforeTrader, afterTrader));

        // Subcase 4: Absent roles claim -> 403
        UUID callerAbsent = UUID.randomUUID();
        String tokenAbsent = TestJwtSupport.createTokenWithoutRolesClaim(callerAbsent);
        DbSnapshot beforeAbsent = takeSnapshot(sku);
        MvcResult resAbsent = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAbsent)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isForbidden())
                .andReturn();
        DbSnapshot afterAbsent = takeSnapshot(sku);
        assertThat(afterAbsent).isEqualTo(beforeAbsent);
        subcases.add(recordSubcase("absent_roles_claim", 403, resAbsent, beforeAbsent, afterAbsent));

        recordScenario("SEC3", subcases);
    }

    // =========================================================================
    // SEC4: Caller A Fresh Purchase, Replay, and Key Conflict
    // =========================================================================
    @Test
    @Order(4)
    @DisplayName("SEC4: Caller A purchases qty 2 from stock 5, replays same key, and conflicts on key reuse with qty 3")
    void sec4_freshReplayAndConflict() throws Exception {
        String sku = "SEC4_IRON";
        seedStock(sku, 5);
        UUID callerA = UUID.randomUUID();
        String tokenA = TestJwtSupport.createValidTraderToken(callerA);
        UUID keyK = UUID.randomUUID();

        List<Map<String, Object>> subcases = new ArrayList<>();

        // Step 1: Fresh purchase (qty 2)
        DbSnapshot snap0 = takeSnapshot(sku);
        MvcResult resFresh = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .header("Idempotency-Key", keyK.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode jsonFresh = objectMapper.readTree(resFresh.getResponse().getContentAsString());
        assertThat(jsonFresh.get("resultCode").asText()).isEqualTo("SUCCESS");
        assertThat(jsonFresh.get("replayed").asBoolean()).isFalse();
        UUID purchaseIdFresh = UUID.fromString(jsonFresh.get("purchaseId").asText());

        DbSnapshot snap1 = takeSnapshot(sku);
        assertThat(snap1.stockAvailable()).isEqualTo(3);
        assertThat(snap1.stockVersion()).isEqualTo(1L);
        assertThat(snap1.purchaseCount()).isEqualTo(1);
        assertThat(snap1.purchaseSumQuantity()).isEqualTo(2);
        assertThat(snap1.idempotencyRowCount()).isEqualTo(1);
        subcases.add(recordSubcase("fresh_purchase", 200, resFresh, snap0, snap1));

        // Step 2: Replay exact same key and payload
        MvcResult resReplay = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .header("Idempotency-Key", keyK.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode jsonReplay = objectMapper.readTree(resReplay.getResponse().getContentAsString());
        assertThat(jsonReplay.get("resultCode").asText()).isEqualTo("SUCCESS");
        assertThat(jsonReplay.get("replayed").asBoolean()).isTrue();
        UUID purchaseIdReplay = UUID.fromString(jsonReplay.get("purchaseId").asText());
        assertThat(purchaseIdReplay).isEqualTo(purchaseIdFresh);

        DbSnapshot snap2 = takeSnapshot(sku);
        assertThat(snap2).isEqualTo(snap1); // No DB changes on replay
        subcases.add(recordSubcase("replay_exact", 200, resReplay, snap1, snap2));

        // Step 3: Reuse same key with different quantity (qty 3) -> 409 IDEMPOTENCY_CONFLICT
        MvcResult resConflict = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .header("Idempotency-Key", keyK.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"quantity\":3}"))
                .andExpect(status().isConflict())
                .andReturn();

        JsonNode jsonConflict = objectMapper.readTree(resConflict.getResponse().getContentAsString());
        assertThat(jsonConflict.get("error").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");

        DbSnapshot snap3 = takeSnapshot(sku);
        assertThat(snap3).isEqualTo(snap2); // No DB changes on conflict
        subcases.add(recordSubcase("conflict_reuse", 409, resConflict, snap2, snap3));

        recordScenario("SEC4", subcases);
    }

    // =========================================================================
    // SEC5: Owner Scoping on GET purchase-requests
    // =========================================================================
    @Test
    @Order(5)
    @DisplayName("SEC5: Caller A buys key K; B reading K returns 404; A reading K returns 200; unknown key returns 404")
    void sec5_ownerScopingOnGet() throws Exception {
        String sku = "SEC5_IRON";
        seedStock(sku, 10);
        UUID callerA = UUID.randomUUID();
        UUID callerB = UUID.randomUUID();
        String tokenA = TestJwtSupport.createValidTraderToken(callerA);
        String tokenB = TestJwtSupport.createValidTraderToken(callerB);
        UUID keyK = UUID.randomUUID();

        List<Map<String, Object>> subcases = new ArrayList<>();

        // Caller A makes purchase with key K
        mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .header("Idempotency-Key", keyK.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"quantity\":2}"))
                .andExpect(status().isOk());

        DbSnapshot snapAfterBuy = takeSnapshot(sku);

        // Subcase 1: Caller B attempts to read A's key K -> 404
        MvcResult resB = mockMvc.perform(get("/api/market/purchase-requests/" + keyK)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andReturn();
        JsonNode jsonB = objectMapper.readTree(resB.getResponse().getContentAsString());
        assertThat(jsonB.get("error").asText()).isEqualTo("PURCHASE_REQUEST_NOT_FOUND");
        DbSnapshot snapAfterB = takeSnapshot(sku);
        assertThat(snapAfterB).isEqualTo(snapAfterBuy);
        subcases.add(recordSubcase("caller_b_read_foreign_key", 404, resB, snapAfterBuy, snapAfterB));

        // Subcase 2: Caller A reads own key K -> 200
        MvcResult resA = mockMvc.perform(get("/api/market/purchase-requests/" + keyK)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode jsonA = objectMapper.readTree(resA.getResponse().getContentAsString());
        assertThat(jsonA.get("idempotencyKey").asText()).isEqualTo(keyK.toString());
        assertThat(jsonA.get("callerId").asText()).isEqualTo(callerA.toString());
        assertThat(jsonA.get("state").asText()).isEqualTo("COMPLETED");
        assertThat(jsonA.get("resultCode").asText()).isEqualTo("SUCCESS");
        assertThat(jsonA.get("sku").asText()).isEqualTo(sku);
        assertThat(jsonA.get("quantity").asInt()).isEqualTo(2);
        DbSnapshot snapAfterA = takeSnapshot(sku);
        assertThat(snapAfterA).isEqualTo(snapAfterBuy);
        subcases.add(recordSubcase("caller_a_read_own_key", 200, resA, snapAfterBuy, snapAfterA));

        // Subcase 3: Read unknown key -> 404 with identical error shape
        UUID randomKey = UUID.randomUUID();
        MvcResult resRandom = mockMvc.perform(get("/api/market/purchase-requests/" + randomKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andReturn();
        JsonNode jsonRandom = objectMapper.readTree(resRandom.getResponse().getContentAsString());
        assertThat(jsonRandom.get("error").asText()).isEqualTo("PURCHASE_REQUEST_NOT_FOUND");
        DbSnapshot snapAfterRandom = takeSnapshot(sku);
        assertThat(snapAfterRandom).isEqualTo(snapAfterBuy);
        subcases.add(recordSubcase("read_unknown_key", 404, resRandom, snapAfterBuy, snapAfterRandom));

        recordScenario("SEC5", subcases);
    }

    // =========================================================================
    // SEC6: Independent Caller Scopes with Same Key
    // =========================================================================
    @Test
    @Order(6)
    @DisplayName("SEC6: Callers A and B use exact same key K; independent purchase scopes and no cross-caller replay")
    void sec6_independentCallerScopesWithSameKey() throws Exception {
        String sku = "SEC6_IRON";
        seedStock(sku, 10);
        UUID callerA = UUID.randomUUID();
        UUID callerB = UUID.randomUUID();
        String tokenA = TestJwtSupport.createValidTraderToken(callerA);
        String tokenB = TestJwtSupport.createValidTraderToken(callerB);
        UUID keyK = UUID.randomUUID();

        List<Map<String, Object>> subcases = new ArrayList<>();

        // Step 1: Caller A purchases qty 2 with key K
        DbSnapshot snap0 = takeSnapshot(sku);
        MvcResult resA = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .header("Idempotency-Key", keyK.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode jsonA = objectMapper.readTree(resA.getResponse().getContentAsString());
        UUID purchaseIdA = UUID.fromString(jsonA.get("purchaseId").asText());
        DbSnapshot snap1 = takeSnapshot(sku);
        assertThat(snap1.stockAvailable()).isEqualTo(8);
        assertThat(snap1.purchaseCount()).isEqualTo(1);
        subcases.add(recordSubcase("caller_a_purchase_key_k", 200, resA, snap0, snap1));

        // Step 2: Caller B uses same key K and same payload to POST
        MvcResult resB = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)
                        .header("Idempotency-Key", keyK.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode jsonB = objectMapper.readTree(resB.getResponse().getContentAsString());
        UUID purchaseIdB = UUID.fromString(jsonB.get("purchaseId").asText());
        assertThat(jsonB.get("replayed").asBoolean()).isFalse(); // MUST NOT replay A's purchase!
        assertThat(purchaseIdB).isNotEqualTo(purchaseIdA);

        DbSnapshot snap2 = takeSnapshot(sku);
        assertThat(snap2.stockAvailable()).isEqualTo(6);
        assertThat(snap2.purchaseCount()).isEqualTo(2);
        assertThat(snap2.purchaseSumQuantity()).isEqualTo(4);
        assertThat(snap2.idempotencyRowCount()).isEqualTo(2);
        subcases.add(recordSubcase("caller_b_purchase_same_key_k", 200, resB, snap1, snap2));

        // Step 3: Caller A reads key K -> gets purchaseIdA
        DbSnapshot snap2BeforeReadA = takeSnapshot(sku);
        MvcResult readA = mockMvc.perform(get("/api/market/purchase-requests/" + keyK)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode readJsonA = objectMapper.readTree(readA.getResponse().getContentAsString());
        assertThat(readJsonA.get("purchaseId").asText()).isEqualTo(purchaseIdA.toString());
        DbSnapshot snap3AfterReadA = takeSnapshot(sku);
        assertThat(snap3AfterReadA).isEqualTo(snap2BeforeReadA);
        subcases.add(recordSubcase("caller_a_read_k", 200, readA, snap2BeforeReadA, snap3AfterReadA));

        // Step 4: Caller B reads key K -> gets purchaseIdB
        DbSnapshot snap3BeforeReadB = takeSnapshot(sku);
        MvcResult readB = mockMvc.perform(get("/api/market/purchase-requests/" + keyK)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode readJsonB = objectMapper.readTree(readB.getResponse().getContentAsString());
        assertThat(readJsonB.get("purchaseId").asText()).isEqualTo(purchaseIdB.toString());
        DbSnapshot snap4AfterReadB = takeSnapshot(sku);
        assertThat(snap4AfterReadB).isEqualTo(snap3BeforeReadB);
        subcases.add(recordSubcase("caller_b_read_k", 200, readB, snap3BeforeReadB, snap4AfterReadB));

        recordScenario("SEC6", subcases);
    }

    // =========================================================================
    // SEC7: Caller Identity Spoofing Immunity (Headers, Body, Query)
    // =========================================================================
    @Test
    @Order(7)
    @DisplayName("SEC7: Token B with X-Caller-Id or query spoofing ignored; body callerId rejected 400; no claim for A")
    void sec7_callerIdentitySpoofingImmunity() throws Exception {
        String sku = "SEC7_IRON";
        seedStock(sku, 10);
        UUID callerA = UUID.randomUUID();
        UUID callerB = UUID.randomUUID();
        String tokenB = TestJwtSupport.createValidTraderToken(callerB);

        List<Map<String, Object>> subcases = new ArrayList<>();

        // Subcase 1: Token B with header X-Caller-Id: callerA -> processed under B, not A
        UUID keyHeader = UUID.randomUUID();
        DbSnapshot snap0 = takeSnapshot(sku);
        MvcResult resHeader = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)
                        .header("X-Caller-Id", callerA.toString())
                        .header("Idempotency-Key", keyHeader.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andReturn();
        DbSnapshot snap1 = takeSnapshot(sku);
        // Verify in DB that row is created for callerB, and ZERO rows exist for callerA
        Integer countA1 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM market_idempotency WHERE caller_id = ?", Integer.class, callerA
        );
        assertThat(countA1).isEqualTo(0);
        subcases.add(recordSubcase("spoof_via_header_ignored", 200, resHeader, snap0, snap1));

        // Subcase 2: Token B with query param ?callerId=callerA -> ignored, processed under B
        UUID keyQuery = UUID.randomUUID();
        MvcResult resQuery = mockMvc.perform(post("/api/market/purchases?callerId=" + callerA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)
                        .header("Idempotency-Key", keyQuery.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andReturn();
        DbSnapshot snap2 = takeSnapshot(sku);
        Integer countA2 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM market_idempotency WHERE caller_id = ?", Integer.class, callerA
        );
        assertThat(countA2).isEqualTo(0);
        subcases.add(recordSubcase("spoof_via_query_ignored", 200, resQuery, snap1, snap2));

        // Subcase 3: Body containing unknown field callerId -> 400 Bad Request
        UUID keyBody = UUID.randomUUID();
        MvcResult resBody = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)
                        .header("Idempotency-Key", keyBody.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"quantity\":2,\"callerId\":\"" + callerA + "\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        DbSnapshot snap3 = takeSnapshot(sku);
        assertThat(snap3).isEqualTo(snap2); // No DB changes on 400
        Integer countA3 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM market_idempotency WHERE caller_id = ?", Integer.class, callerA
        );
        assertThat(countA3).isEqualTo(0);
        subcases.add(recordSubcase("spoof_via_body_rejected_400", 400, resBody, snap2, snap3));

        recordScenario("SEC7", subcases);
    }

    // =========================================================================
    // SEC8: Input Validation Matrix and NOT_AVAILABLE Replay
    // =========================================================================
    @Test
    @Order(8)
    @DisplayName("SEC8: Input validation matrix (missing/invalid key, body, decimal qty, SKU bounds) and NOT_AVAILABLE replay")
    void sec8_inputValidationAndNotAvailableReplay() throws Exception {
        String sku = "SEC8_IRON";
        seedStock(sku, 10);
        UUID caller = UUID.randomUUID();
        String token = TestJwtSupport.createValidTraderToken(caller);

        List<Map<String, Object>> subcases = new ArrayList<>();

        // 1. Missing Idempotency-Key header -> 400
        DbSnapshot s0 = takeSnapshot(sku);
        MvcResult r1 = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"quantity\":2}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        DbSnapshot s1 = takeSnapshot(sku);
        assertThat(s1).isEqualTo(s0);
        subcases.add(recordSubcase("missing_idempotency_key", 400, r1, s0, s1));

        // 2. Invalid UUID in Idempotency-Key header -> 400
        MvcResult r2 = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"quantity\":2}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        DbSnapshot s2 = takeSnapshot(sku);
        assertThat(s2).isEqualTo(s0);
        subcases.add(recordSubcase("invalid_uuid_idempotency_key", 400, r2, s0, s2));

        // 3. Malformed JSON body -> 400
        MvcResult r3 = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{malformed-json"))
                .andExpect(status().isBadRequest())
                .andReturn();
        DbSnapshot s3 = takeSnapshot(sku);
        assertThat(s3).isEqualTo(s0);
        subcases.add(recordSubcase("malformed_json_body", 400, r3, s0, s3));

        // 4. Quantity = 0 -> 400
        MvcResult r4 = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        DbSnapshot s4 = takeSnapshot(sku);
        assertThat(s4).isEqualTo(s0);
        subcases.add(recordSubcase("quantity_zero", 400, r4, s0, s4));

        // 5. Quantity = -1 -> 400
        MvcResult r5 = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"quantity\":-1}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        DbSnapshot s5 = takeSnapshot(sku);
        assertThat(s5).isEqualTo(s0);
        subcases.add(recordSubcase("quantity_negative", 400, r5, s0, s5));

        // 6. Quantity = null -> 400
        MvcResult r6 = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"quantity\":null}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        DbSnapshot s6 = takeSnapshot(sku);
        assertThat(s6).isEqualTo(s0);
        subcases.add(recordSubcase("quantity_null", 400, r6, s0, s6));

        // 7. Quantity = 1.5 (decimal) -> 400 (strict Jackson coercion rejection)
        MvcResult r7 = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"quantity\":1.5}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        DbSnapshot s7 = takeSnapshot(sku);
        assertThat(s7).isEqualTo(s0);
        subcases.add(recordSubcase("quantity_decimal_coercion_rejected", 400, r7, s0, s7));

        // 8. SKU = blank -> 400
        MvcResult r8 = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"   \",\"quantity\":2}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        DbSnapshot s8 = takeSnapshot(sku);
        assertThat(s8).isEqualTo(s0);
        subcases.add(recordSubcase("sku_blank", 400, r8, s0, s8));

        // 9. SKU = 65 characters (exceeds max 64) -> 400
        String longSku = "A".repeat(65);
        MvcResult r9 = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + longSku + "\",\"quantity\":2}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        DbSnapshot s9 = takeSnapshot(sku);
        assertThat(s9).isEqualTo(s0);
        subcases.add(recordSubcase("sku_too_long", 400, r9, s0, s9));

        // 10. NOT_AVAILABLE scenario and replay after restock
        String outSku = "SEC8_OUT_OF_STOCK";
        seedStock(outSku, 0);
        UUID notAvailKey = UUID.randomUUID();

        // 10a: First attempt with stock 0 -> 200 NOT_AVAILABLE
        DbSnapshot snapOut0 = takeSnapshot(outSku);
        MvcResult rOut1 = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", notAvailKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + outSku + "\",\"quantity\":1}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode jsonOut1 = objectMapper.readTree(rOut1.getResponse().getContentAsString());
        assertThat(jsonOut1.get("resultCode").asText()).isEqualTo("NOT_AVAILABLE");
        assertThat(jsonOut1.get("purchaseId").isNull()).isTrue();
        assertThat(jsonOut1.get("replayed").asBoolean()).isFalse();

        DbSnapshot snapOut1 = takeSnapshot(outSku);
        assertThat(snapOut1.stockAvailable()).isEqualTo(0);
        assertThat(snapOut1.purchaseCount()).isEqualTo(0);
        assertThat(snapOut1.idempotencyRowCount()).isEqualTo(1);
        subcases.add(recordSubcase("not_available_fresh", 200, rOut1, snapOut0, snapOut1));

        // 10b: Restock via direct SQL fixture
        jdbcTemplate.update("UPDATE market_stock SET available = 10 WHERE sku = ?", outSku);

        // 10c: Replay same key -> still 200 NOT_AVAILABLE, stock remains 10 (not purchased)
        DbSnapshot snapOutRestocked = takeSnapshot(outSku);
        assertThat(snapOutRestocked.stockAvailable()).isEqualTo(10);

        MvcResult rOut2 = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", notAvailKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + outSku + "\",\"quantity\":1}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode jsonOut2 = objectMapper.readTree(rOut2.getResponse().getContentAsString());
        assertThat(jsonOut2.get("resultCode").asText()).isEqualTo("NOT_AVAILABLE");
        assertThat(jsonOut2.get("purchaseId").isNull()).isTrue();
        assertThat(jsonOut2.get("replayed").asBoolean()).isTrue();

        DbSnapshot snapOutFinal = takeSnapshot(outSku);
        assertThat(snapOutFinal.stockAvailable()).isEqualTo(10); // NOT deducted!
        assertThat(snapOutFinal.purchaseCount()).isEqualTo(0);
        subcases.add(recordSubcase("not_available_replayed_after_restock", 200, rOut2, snapOutRestocked, snapOutFinal));

        recordScenario("SEC8", subcases);
    }

    // =========================================================================
    // SEC9: Statelessness, Session Isolation, and Public Route Retention
    // =========================================================================
    @Test
    @Order(9)
    @DisplayName("SEC9: Authentication not retained across requests (stateless); public GET intact; unmapped routes denied")
    void sec9_statelessnessAndRouteIsolation() throws Exception {
        String sku = "SEC9_IRON";
        seedStock(sku, 10);
        UUID caller = UUID.randomUUID();
        String token = TestJwtSupport.createValidTraderToken(caller);

        List<Map<String, Object>> subcases = new ArrayList<>();

        // 1. First request with valid token -> 200
        MvcResult resAuthed = mockMvc.perform(post("/api/market/purchases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"quantity\":1}"))
                .andExpect(status().isOk())
                .andReturn();
        subcases.add(recordSubcase("stateless_request_with_token", 200, resAuthed, null, null));

        // 2. Immediate next request without token on same client -> 401 (proves NO session retention)
        MvcResult resUnauthed = mockMvc.perform(post("/api/market/purchases")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"" + sku + "\",\"quantity\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE))
                .andReturn();
        subcases.add(recordSubcase("stateless_request_without_token_rejected_401", 401, resUnauthed, null, null));

        // 3. Existing public GET /api/lab/entries/{id} -> 200 without token
        MvcResult resPublic = mockMvc.perform(get("/api/lab/entries/entry-01"))
                .andExpect(status().isOk())
                .andReturn();
        subcases.add(recordSubcase("public_get_lab_entry_200", 200, resPublic, null, null));

        // 4. Public GET non-existent entry -> 404 without token
        MvcResult resPublic404 = mockMvc.perform(get("/api/lab/entries/non-existent-id"))
                .andExpect(status().isNotFound())
                .andReturn();
        subcases.add(recordSubcase("public_get_lab_entry_404", 404, resPublic404, null, null));

        // 5. Undeclared route /api/unknown -> 401 or 403 (denyAll)
        MvcResult resUndeclared = mockMvc.perform(get("/api/unknown"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        subcases.add(recordSubcase("undeclared_route_denied", 401, resUndeclared, null, null));

        recordScenario("SEC9", subcases);
    }

    // =========================================================================
    // Evidence Export
    // =========================================================================
    @AfterAll
    static void exportEvidence() throws Exception {
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

        String runId = System.getProperty("lab.runId", "ad-hoc-" + Instant.now().toEpochMilli());

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", "1.0.0");
        evidence.put("runId", runId);
        evidence.put("generatedAt", Instant.now().toString());
        evidence.put("scenarioCount", OBSERVATIONS.size());
        evidence.put("scenarios", OBSERVATIONS);

        File outputFile = new File(dir, "security-observations.json");
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, evidence);

        File targetDir = new File("target/lab-evidence");
        if (!targetDir.exists() && new File("backend-app/target").exists()) {
            targetDir = new File("backend-app/target/lab-evidence");
        }
        targetDir.mkdirs();
        File localFile = new File(targetDir, "security-observations.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(localFile, evidence);

        System.out.println("Exported security evidence to: " + outputFile.getAbsolutePath());
    }

    private static Map<String, Object> recordSubcase(
            String variantName,
            int expectedStatus,
            MvcResult result,
            DbSnapshot before,
            DbSnapshot after
    ) {
        Map<String, Object> sc = new LinkedHashMap<>();
        sc.put("variant", variantName);
        sc.put("expectedStatus", expectedStatus);
        int observedStatus = result.getResponse().getStatus();
        sc.put("observedStatus", observedStatus);
        sc.put("bearerChallengePresent", result.getResponse().containsHeader(HttpHeaders.WWW_AUTHENTICATE));

        String content = "";
        try {
            content = result.getResponse().getContentAsString();
        } catch (Exception ignored) {}
        sc.put("responseBody", content);

        if (before != null && after != null) {
            Map<String, Object> beforeMap = new LinkedHashMap<>();
            beforeMap.put("stockAvailable", before.stockAvailable());
            beforeMap.put("stockVersion", before.stockVersion());
            beforeMap.put("purchaseCount", before.purchaseCount());
            beforeMap.put("purchaseSumQuantity", before.purchaseSumQuantity());
            beforeMap.put("idempotencyRowCount", before.idempotencyRowCount());
            sc.put("before", beforeMap);

            Map<String, Object> afterMap = new LinkedHashMap<>();
            afterMap.put("stockAvailable", after.stockAvailable());
            afterMap.put("stockVersion", after.stockVersion());
            afterMap.put("purchaseCount", after.purchaseCount());
            afterMap.put("purchaseSumQuantity", after.purchaseSumQuantity());
            afterMap.put("idempotencyRowCount", after.idempotencyRowCount());
            sc.put("after", afterMap);

            sc.put("stateUnchanged", before.equals(after));
        }

        return sc;
    }

    private static void recordScenario(String scenarioId, List<Map<String, Object>> subcases) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("caseId", scenarioId);
        entry.put("scenarioId", scenarioId);
        entry.put("subcaseCount", subcases.size());
        entry.put("subcases", subcases);
        OBSERVATIONS.add(entry);
    }
}
