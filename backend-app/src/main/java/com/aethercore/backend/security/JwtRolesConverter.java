package com.aethercore.backend.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Converts JWT 'roles' claim array to Spring Security GrantedAuthorities with 'ROLE_' prefix.
 * e.g. ["TRADER"] -> [ROLE_TRADER].
 * Strictly verifies claim types to prevent type confusion attacks.
 */
public class JwtRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    public static final String ROLES_CLAIM = "roles";
    public static final String ROLE_PREFIX = "ROLE_";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        if (!jwt.hasClaim(ROLES_CLAIM)) {
            return Collections.emptyList();
        }

        Object rawRoles = jwt.getClaim(ROLES_CLAIM);
        if (rawRoles == null) {
            return Collections.emptyList();
        }

        if (!(rawRoles instanceof Collection<?> rolesCollection)) {
            OAuth2Error error = new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_TOKEN,
                    "The '" + ROLES_CLAIM + "' claim must be a JSON array of strings",
                    null
            );
            throw new OAuth2AuthenticationException(error);
        }

        List<GrantedAuthority> authorities = new ArrayList<>();
        for (Object item : rolesCollection) {
            if (!(item instanceof String roleName)) {
                OAuth2Error error = new OAuth2Error(
                        OAuth2ErrorCodes.INVALID_TOKEN,
                        "Elements in '" + ROLES_CLAIM + "' claim array must be strings",
                        null
                );
                throw new OAuth2AuthenticationException(error);
            }
            String trimmed = roleName.trim();
            if (!trimmed.isEmpty()) {
                String authorityName = trimmed.startsWith(ROLE_PREFIX) ? trimmed : (ROLE_PREFIX + trimmed);
                authorities.add(new SimpleGrantedAuthority(authorityName));
            }
        }

        return Collections.unmodifiableList(authorities);
    }
}
