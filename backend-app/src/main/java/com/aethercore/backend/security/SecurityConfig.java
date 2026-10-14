package com.aethercore.backend.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.security.interfaces.RSAPublicKey;

/**
 * Spring Security configuration establishing:
 * 1. Stateless session policy (no session cookies, no session affinity).
 * 2. Disabled CSRF (Bearer token authentication in HTTP Authorization header).
 * 3. Public access limited to GET /api/lab/entries/** and error rendering.
 * 4. Protected market purchase endpoints requiring ROLE_TRADER.
 * 5. Strict JWT decoding and authority mapping.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AetherSecurityProperties.class)
public class SecurityConfig {

    @Bean
    public JwtRolesConverter jwtRolesConverter() {
        return new JwtRolesConverter();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter(JwtRolesConverter jwtRolesConverter) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwtRolesConverter);
        return converter;
    }

    @Bean
    public JwtDecoder jwtDecoder(AetherSecurityProperties properties) {
        RSAPublicKey publicKey = properties.getParsedPublicKey();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        decoder.setJwtValidator(AetherJwtValidators.create(properties));
        return decoder;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/lab/entries/**").permitAll()
                        .requestMatchers("/api/market/**").hasRole("TRADER")
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                );

        return http.build();
    }
}
