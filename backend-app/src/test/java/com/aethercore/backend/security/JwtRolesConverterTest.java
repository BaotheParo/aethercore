package com.aethercore.backend.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test validating JwtRolesConverter authority mapping and type checking:
 * 1. [TRADER] maps to ROLE_TRADER (not TRADER, not ROLE_ROLE_TRADER).
 * 2. Missing/empty roles return empty collection.
 * 3. Invalid role types throw OAuth2AuthenticationException.
 */
class JwtRolesConverterTest {

    private final JwtRolesConverter converter = new JwtRolesConverter();

    @Test
    @DisplayName("Roles ['TRADER'] maps to single GrantedAuthority 'ROLE_TRADER'")
    void shouldMapTraderRoleToPrefixedAuthority() {
        Jwt jwt = createJwt(Map.of("roles", List.of("TRADER")));
        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_TRADER");
    }

    @Test
    @DisplayName("Pre-prefixed role 'ROLE_TRADER' does not double-prefix to 'ROLE_ROLE_TRADER'")
    void shouldNotDoublePrefixAlreadyPrefixedRole() {
        Jwt jwt = createJwt(Map.of("roles", List.of("ROLE_TRADER")));
        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_TRADER");
    }

    @Test
    @DisplayName("Multiple roles ['TRADER', 'VIEWER'] map to ['ROLE_TRADER', 'ROLE_VIEWER']")
    void shouldMapMultipleRoles() {
        Jwt jwt = createJwt(Map.of("roles", List.of("TRADER", "VIEWER")));
        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_TRADER", "ROLE_VIEWER");
    }

    @Test
    @DisplayName("Missing 'roles' claim returns empty authorities collection")
    void shouldReturnEmptyWhenRolesClaimMissing() {
        Jwt jwt = createJwt(Map.of());
        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).isEmpty();
    }

    @Test
    @DisplayName("Empty 'roles' claim array returns empty authorities collection")
    void shouldReturnEmptyWhenRolesClaimEmpty() {
        Jwt jwt = createJwt(Map.of("roles", List.of()));
        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).isEmpty();
    }

    @Test
    @DisplayName("Non-collection 'roles' claim (e.g. String) throws OAuth2AuthenticationException")
    void shouldThrowWhenRolesClaimIsNotCollection() {
        Jwt jwt = createJwt(Map.of("roles", "TRADER"));

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("must be a JSON array of strings");
    }

    @Test
    @DisplayName("Roles collection with non-string elements throws OAuth2AuthenticationException")
    void shouldThrowWhenRolesClaimContainsNonStringElements() {
        Jwt jwt = createJwt(Map.of("roles", List.of(12345)));

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("must be strings");
    }

    private Jwt createJwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("mock.token.value")
                .header("alg", "RS256")
                .subject("00000000-0000-0000-0000-000000000001")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claims(c -> c.putAll(claims))
                .build();
    }
}
