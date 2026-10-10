package com.aethercore.backend.damage;

import com.aethercore.core.m1e3.CharacterStats;
import com.aethercore.core.m1e4.DamageStrategy;
import com.aethercore.core.m1e4.PhysicalDamageStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1-E4 Spring DI Wiring Test:
 * Pure Spring Context test verifying bean registration, Constructor Injection,
 * Qualifier disambiguation, and ambiguity handling without starting external Docker containers.
 */
@SpringBootTest(classes = {DamageConfig.class, DamageCalculationService.class})
public class DamageStrategyWiringTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DamageCalculationService damageCalculationService;

    @Autowired
    @Qualifier("physicalDamageStrategy")
    private DamageStrategy physicalStrategyBean;

    @Autowired
    @Qualifier("magicalDamageStrategy")
    private DamageStrategy magicalStrategyBean;

    @Test
    @DisplayName("Spring context loads DamageStrategy beans and wires service via Constructor Injection")
    void contextLoadsAndWiresService() {
        assertNotNull(damageCalculationService);
        assertNotNull(physicalStrategyBean);
        assertNotNull(magicalStrategyBean);

        // Verify service received physicalDamageStrategy as configured by @Qualifier
        assertSame(physicalStrategyBean, damageCalculationService.getDamageStrategy());
        assertTrue(damageCalculationService.getDamageStrategy() instanceof PhysicalDamageStrategy);

        // Verify computation through Spring-managed bean
        CharacterStats stats = new CharacterStats(20, 10, 10);
        assertEquals(45L, damageCalculationService.calculateDamage(stats));
    }

    @Test
    @DisplayName("Requesting DamageStrategy by type without qualifier throws NoUniqueBeanDefinitionException")
    void ambiguousBeanLookupThrowsException() {
        // Since there are 2 beans of type DamageStrategy (physicalDamageStrategy and magicalDamageStrategy),
        // querying by type alone without bean name or @Primary must fail with NoUniqueBeanDefinitionException
        assertThrows(NoUniqueBeanDefinitionException.class, () -> {
            applicationContext.getBean(DamageStrategy.class);
        }, "Querying ambiguous beans by type alone without qualifier must throw NoUniqueBeanDefinitionException");
    }
}
