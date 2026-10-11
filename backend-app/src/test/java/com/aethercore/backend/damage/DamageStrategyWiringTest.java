package com.aethercore.backend.damage;

import com.aethercore.core.m1e3.CharacterStats;
import com.aethercore.core.m1e4.DamageStrategy;
import com.aethercore.core.m1e4.MagicalDamageStrategy;
import com.aethercore.core.m1e4.PhysicalDamageStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1-E4 Spring DI Wiring Test:
 * Pure Spring Context test using AnnotationConfigApplicationContext to verify:
 * 1. Successful Constructor Injection disambiguation using @Qualifier.
 * 2. Real Constructor Injection FAILURE when multiple beans exist without qualifier.
 */
public class DamageStrategyWiringTest {

    // === Context 1: Qualified Configuration (Success) ===
    @Configuration
    static class QualifiedConfig {
        @Bean("physicalDamageStrategy")
        public DamageStrategy physicalDamageStrategy() {
            return new PhysicalDamageStrategy();
        }

        @Bean("magicalDamageStrategy")
        public DamageStrategy magicalDamageStrategy() {
            return new MagicalDamageStrategy();
        }

        @Bean
        public QualifiedDamageService qualifiedDamageService(
                @Qualifier("physicalDamageStrategy") DamageStrategy strategy) {
            return new QualifiedDamageService(strategy);
        }
    }

    static class QualifiedDamageService {
        private final DamageStrategy strategy;

        public QualifiedDamageService(DamageStrategy strategy) {
            this.strategy = strategy;
        }

        public long calculateDamage(CharacterStats stats) {
            return strategy.calculateDamage(stats);
        }

        public DamageStrategy getStrategy() {
            return strategy;
        }
    }

    // === Context 2: Ambiguous Configuration (Constructor Injection Failure) ===
    @Configuration
    static class AmbiguousConfig {
        @Bean("physicalDamageStrategy")
        public DamageStrategy physicalDamageStrategy() {
            return new PhysicalDamageStrategy();
        }

        @Bean("magicalDamageStrategy")
        public DamageStrategy magicalDamageStrategy() {
            return new MagicalDamageStrategy();
        }

        @Bean
        public AmbiguousDamageService ambiguousDamageService(DamageStrategy chosenStrategy) {
            // Parameter name 'chosenStrategy' does NOT match any bean name, and no @Qualifier is present
            return new AmbiguousDamageService(chosenStrategy);
        }
    }

    static class AmbiguousDamageService {
        private final DamageStrategy strategy;

        public AmbiguousDamageService(DamageStrategy strategy) {
            this.strategy = strategy;
        }
    }

    @Test
    @DisplayName("Context 1: Consumer with @Qualifier successfully resolves bean and computes damage")
    void qualifiedInjectionSucceeds() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(QualifiedConfig.class)) {
            QualifiedDamageService service = context.getBean(QualifiedDamageService.class);
            assertNotNull(service);

            DamageStrategy physicalBean = context.getBean("physicalDamageStrategy", DamageStrategy.class);
            assertSame(physicalBean, service.getStrategy());
            assertTrue(service.getStrategy() instanceof PhysicalDamageStrategy);

            // Compute with stats (10, 12, 4) -> 2*10 + 4 = 24L
            CharacterStats stats = new CharacterStats(10, 12, 4);
            assertEquals(24L, service.calculateDamage(stats));
        }
    }

    @Test
    @DisplayName("Context 2: Consumer without @Qualifier fails context initialization with NoUniqueBeanDefinitionException")
    void ambiguousConstructorInjectionFailsInitialization() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        try {
            context.register(AmbiguousConfig.class);

            // Refreshing the context triggers eager singleton creation.
            // Spring fails when creating ambiguousDamageService bean due to two candidates.
            BeanCreationException ex = assertThrows(BeanCreationException.class, context::refresh,
                    "Context refresh must fail when constructor dependency cannot be uniquely resolved");

            // Verify that NoUniqueBeanDefinitionException is present in the cause chain
            Throwable rootCause = ex.getRootCause();
            assertNotNull(rootCause);
            assertTrue(rootCause instanceof NoUniqueBeanDefinitionException,
                    "Root cause must be NoUniqueBeanDefinitionException (got: " + rootCause.getClass().getName() + ")");
        } finally {
            context.close();
        }
    }
}
