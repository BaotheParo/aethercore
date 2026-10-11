package com.aethercore.backend.market;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency and Transaction Integration Test for Market Purchase (M2-E1A).
 * Uses real PostgreSQL container via Testcontainers to verify:
 * 1. Stale read-then-write (lost update) in broken service violating invariant.
 * 2. Atomic conditional update in fixed service preserving invariant under concurrency.
 * 3. Validation and edge case handling.
 * 4. Atomicity and rollback on constraint failure.
 * 5. Multi-attempt correctness under concurrency.
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

    @Test
    @DisplayName("B2. Broken Service: two concurrent buyers on stock 1 reproduce lost update and violate invariant")
    void brokenService_twoBuyersOnOneStock_reproducesLostUpdate() throws Exception {
        String sku = "REDSTONE";
        seedStock(sku, 1L);

        CyclicBarrier barrier = new CyclicBarrier(2);
        BrokenMarketPurchaseService brokenService = new BrokenMarketPurchaseService(jdbcTemplate, transactionManager, barrier);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PurchaseResult> f1 = executor.submit(() -> brokenService.purchase(UUID.randomUUID(), sku, 1));
            Future<PurchaseResult> f2 = executor.submit(() -> brokenService.purchase(UUID.randomUUID(), sku, 1));

            PurchaseResult r1 = f1.get(5, TimeUnit.SECONDS);
            PurchaseResult r2 = f2.get(5, TimeUnit.SECONDS);

            // Both workers read 1, both wrote available = 0, both committed
            assertEquals(PurchaseResult.SUCCESS, r1);
            assertEquals(PurchaseResult.SUCCESS, r2);

            long finalStock = getStock(sku);
            long totalQuantity = getSumPurchasedQuantity(sku);
            int purchaseCount = getPurchaseCount(sku);

            assertEquals(0L, finalStock, "Final stock is 0");
            assertEquals(2, purchaseCount, "2 purchases were recorded");
            assertEquals(2L, totalQuantity, "Total quantity purchased is 2");

            // INVARIANT VIOLATION: finalStock (0) + totalQuantity (2) != initialStock (1)
            assertNotEquals(1L, finalStock + totalQuantity, "Broken service violates invariant due to lost update");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("B3. Fixed Service: two concurrent buyers on stock 1 preserve invariant (1 SUCCESS, 1 NOT_AVAILABLE)")
    void fixedService_twoBuyersOnOneStock_preservesInvariant() throws Exception {
        String sku = "REDSTONE";
        seedStock(sku, 1L);

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<PurchaseResult> task = () -> {
                startGate.await(5, TimeUnit.SECONDS);
                return fixedService.purchase(UUID.randomUUID(), sku, 1);
            };

            Future<PurchaseResult> f1 = executor.submit(task);
            Future<PurchaseResult> f2 = executor.submit(task);

            startGate.countDown();

            PurchaseResult r1 = f1.get(5, TimeUnit.SECONDS);
            PurchaseResult r2 = f2.get(5, TimeUnit.SECONDS);

            List<PurchaseResult> results = List.of(r1, r2);
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
            executor.shutdownNow();
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
        assertThrows(DataAccessException.class, () -> {
            fixedService.purchase(fixedPurchaseId, sku, 1);
        });

        // 3. Verify complete rollback: stock is NOT decremented to 0, remains 1!
        assertEquals(1L, getStock(sku), "Stock must remain 1 after transaction rollback");
        assertEquals(1, getPurchaseCount(sku), "Purchase count must remain 1");
    }

    @Test
    @DisplayName("B4. Concurrent load: 20 attempts for 1 unit on stock 5 with 4 workers")
    void fixedService_concurrentLoad_twentyAttemptsOnFiveStock() throws Exception {
        String sku = "OBSIDIAN";
        seedStock(sku, 5L);

        int attempts = 20;
        int poolSize = 4;
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        CountDownLatch startGate = new CountDownLatch(1);

        List<Future<PurchaseResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                futures.add(executor.submit(() -> {
                    startGate.await(5, TimeUnit.SECONDS);
                    return fixedService.purchase(UUID.randomUUID(), sku, 1);
                }));
            }

            startGate.countDown();

            int successCount = 0;
            int notAvailableCount = 0;
            for (Future<PurchaseResult> f : futures) {
                PurchaseResult res = f.get(10, TimeUnit.SECONDS);
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
            executor.shutdownNow();
        }
    }
}
