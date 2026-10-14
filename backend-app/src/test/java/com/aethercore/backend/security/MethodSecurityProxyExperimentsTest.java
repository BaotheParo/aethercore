package com.aethercore.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure Spring AOP / Method Security experiment (test-only, no Docker required).
 * Proves that:
 * 1. Calls through bean proxy are intercepted by Spring Security advice.
 * 2. Internal 'this' calls within the target bean bypass the proxy and advice (self-invocation trap).
 * 3. Calls from an external collaborator wrapper bean pass through the proxy and are intercepted.
 * 4. Positive control under TRADER passes through the proxy and increments the counter.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = MethodSecurityProxyExperimentsTest.TestAopConfig.class)
public class MethodSecurityProxyExperimentsTest {

    @Configuration
    @EnableMethodSecurity
    static class TestAopConfig {

        @Bean
        public GuardedTargetBean guardedTargetBean() {
            return new GuardedTargetBean();
        }

        @Bean
        public GuardedTargetWrapper guardedTargetWrapper(GuardedTargetBean guardedTargetBean) {
            return new GuardedTargetWrapper(guardedTargetBean);
        }
    }

    public static class GuardedTargetBean {
        private final AtomicInteger counter = new AtomicInteger(0);

        @PreAuthorize("hasRole('TRADER')")
        public int guardedAction() {
            return counter.incrementAndGet();
        }

        public int callGuardedInternally() {
            // Self-invocation trap: 'this' call bypasses the Spring AOP proxy!
            return this.guardedAction();
        }

        public int getCounter() {
            return counter.get();
        }

        public void resetCounter() {
            counter.set(0);
        }
    }

    public static class GuardedTargetWrapper {
        private final GuardedTargetBean guardedBeanProxy;

        public GuardedTargetWrapper(GuardedTargetBean guardedBeanProxy) {
            this.guardedBeanProxy = guardedBeanProxy;
        }

        public int callGuardedViaProxy() {
            // External call: passes through the Spring AOP proxy
            return guardedBeanProxy.guardedAction();
        }
    }

    @Autowired
    private GuardedTargetBean guardedTargetBean;

    @Autowired
    private GuardedTargetWrapper guardedTargetWrapper;

    private static final List<Map<String, Object>> recordedSubcases = new ArrayList<>();

    @BeforeEach
    void setUp() {
        guardedTargetBean.resetCounter();
    }

    @Test
    @DisplayName("MS3: Self-invocation bypasses Spring AOP proxy; external calls intercepted")
    @WithMockUser(username = "viewer-user", roles = {"VIEWER"})
    void ms3_selfInvocationBypassUnderViewer() {
        assertThat(AopUtils.isAopProxy(guardedTargetBean)).isTrue();
        assertThat(AopUtils.isCglibProxy(guardedTargetBean)).isTrue();

        // 1. Direct call on bean proxy under VIEWER -> Intercepted -> AccessDeniedException (counter 0 -> 0)
        int before1 = guardedTargetBean.getCounter();
        assertThatThrownBy(() -> guardedTargetBean.guardedAction())
                .isInstanceOf(AccessDeniedException.class);
        int after1 = guardedTargetBean.getCounter();
        assertThat(after1).isEqualTo(before1).isEqualTo(0);
        recordSubcase("direct_proxy_call_viewer_denied", "EXTERNAL_BEAN_PROXY", "ROLE_VIEWER",
                "guardedAction", "AccessDeniedException", before1, after1, true);

        // 2. Call unannotated method that calls this.guardedAction() -> Bypasses proxy! (counter 0 -> 1)
        int before2 = guardedTargetBean.getCounter();
        int result2 = guardedTargetBean.callGuardedInternally();
        int after2 = guardedTargetBean.getCounter();
        assertThat(result2).isEqualTo(1);
        assertThat(after2).isEqualTo(1);
        recordSubcase("self_invocation_internal_call_viewer_bypassed", "INTERNAL_THIS_CALL", "ROLE_VIEWER",
                "callGuardedInternally", "SUCCESS_BYPASS", before2, after2, false);

        // 3. Call via external wrapper bean -> Intercepted -> AccessDeniedException (counter 1 -> 1)
        int before3 = guardedTargetBean.getCounter();
        assertThatThrownBy(() -> guardedTargetWrapper.callGuardedViaProxy())
                .isInstanceOf(AccessDeniedException.class);
        int after3 = guardedTargetBean.getCounter();
        assertThat(after3).isEqualTo(before3).isEqualTo(1);
        recordSubcase("external_wrapper_call_viewer_denied", "EXTERNAL_WRAPPER_PROXY", "ROLE_VIEWER",
                "callGuardedViaProxy", "AccessDeniedException", before3, after3, true);
    }

    @Test
    @DisplayName("MS3 Positive Control: Call on bean proxy under TRADER succeeds and increments counter")
    @WithMockUser(username = "trader-user", roles = {"TRADER"})
    void ms3_positiveControlUnderTrader() {
        assertThat(AopUtils.isAopProxy(guardedTargetBean)).isTrue();

        int before = guardedTargetBean.getCounter();
        int result = guardedTargetBean.guardedAction();
        int after = guardedTargetBean.getCounter();

        assertThat(result).isEqualTo(1);
        assertThat(after).isEqualTo(1);
        recordSubcase("direct_proxy_call_trader_success", "EXTERNAL_BEAN_PROXY", "ROLE_TRADER",
                "guardedAction", "SUCCESS_AUTHORIZED", before, after, false);
    }

    private void recordSubcase(
            String variant,
            String invocationPath,
            String role,
            String targetMethod,
            String outcome,
            int counterBefore,
            int counterAfter,
            boolean intercepted
    ) {
        Map<String, Object> sc = new LinkedHashMap<>();
        sc.put("variant", variant);
        sc.put("invocationPath", invocationPath);
        sc.put("role", role);
        sc.put("targetMethod", targetMethod);
        sc.put("outcome", outcome);
        sc.put("counterBefore", counterBefore);
        sc.put("counterAfter", counterAfter);
        sc.put("intercepted", intercepted);
        sc.put("stateUnchanged", counterBefore == counterAfter);
        recordedSubcases.add(sc);
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
        payload.put("proxyMechanism", "SPRING_CGLIB_PROXY");
        payload.put("methodSecurityInterceptor", "AuthorizationManagerBeforeMethodInterceptor");
        payload.put("subcaseCount", recordedSubcases.size());
        payload.put("subcases", recordedSubcases);

        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        File outFile = new File(targetDir, "method-security-proxy-observations.json");
        try {
            om.writeValue(outFile, payload);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write method-security-proxy-observations.json", e);
        }
    }
}
