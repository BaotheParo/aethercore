package com.aethercore.backend.security;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Initializer providing fallback ephemeral RSA public key trust properties
 * for any @SpringBootTest test execution that does not configure an explicit key.
 * Guarantees existing application context integration tests continue starting seamlessly.
 */
public class TestSecurityContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        String existingKey = applicationContext.getEnvironment().getProperty("aethercore.security.jwt.public-key-pem");
        if (existingKey == null || existingKey.trim().isEmpty()) {
            Map<String, Object> props = new HashMap<>();
            props.put("aethercore.security.jwt.issuer", TestJwtSupport.DEFAULT_ISSUER);
            props.put("aethercore.security.jwt.audience", TestJwtSupport.DEFAULT_AUDIENCE);
            props.put("aethercore.security.jwt.public-key-pem", TestJwtSupport.TEST_PUBLIC_KEY_PEM);
            applicationContext.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("testEphemeralSecurityProperties", props)
            );
        }
    }
}
