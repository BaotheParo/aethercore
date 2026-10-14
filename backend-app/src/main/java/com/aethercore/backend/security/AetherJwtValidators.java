package com.aethercore.backend.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Creates delegating JWT validator enforcing:
 * 1. Timestamp validity (exp & nbf) with configurable clock skew.
 * 2. Mandatory existence of exp claim.
 * 3. Exact issuer match.
 * 4. Audience inclusion.
 * 5. Canonical UUID format for subject (sub) without untracked trimming.
 * 6. Valid string types for 'roles' claim array if present.
 */
public final class AetherJwtValidators {

    private AetherJwtValidators() {}

    public static OAuth2TokenValidator<Jwt> create(AetherSecurityProperties properties) {
        Duration clockSkew = Duration.ofSeconds(properties.getClockSkewSeconds());

        JwtTimestampValidator timestampValidator = new JwtTimestampValidator(clockSkew);
        JwtIssuerValidator issuerValidator = new JwtIssuerValidator(properties.getIssuer());

        OAuth2TokenValidator<Jwt> audienceValidator = token -> {
            List<String> audience = token.getAudience();
            if (audience == null || !audience.contains(properties.getAudience())) {
                OAuth2Error error = new OAuth2Error(
                        OAuth2ErrorCodes.INVALID_TOKEN,
                        "The required audience '" + properties.getAudience() + "' is missing or invalid",
                        null
                );
                return OAuth2TokenValidatorResult.failure(error);
            }
            return OAuth2TokenValidatorResult.success();
        };

        OAuth2TokenValidator<Jwt> requiredClaimsValidator = token -> {
            // exp must be explicitly present
            if (token.getExpiresAt() == null) {
                OAuth2Error error = new OAuth2Error(
                        OAuth2ErrorCodes.INVALID_TOKEN,
                        "Missing mandatory expiration claim ('exp')",
                        null
                );
                return OAuth2TokenValidatorResult.failure(error);
            }

            // sub must be present and strictly a 36-character canonical UUID (no whitespace, no shortened forms)
            String sub = token.getSubject();
            if (sub == null || sub.length() != 36) {
                OAuth2Error error = new OAuth2Error(
                        OAuth2ErrorCodes.INVALID_TOKEN,
                        "Subject claim ('sub') must be a 36-character canonical UUID string",
                        null
                );
                return OAuth2TokenValidatorResult.failure(error);
            }
            try {
                UUID parsed = UUID.fromString(sub);
                if (!parsed.toString().equalsIgnoreCase(sub)) {
                    OAuth2Error error = new OAuth2Error(
                            OAuth2ErrorCodes.INVALID_TOKEN,
                            "Subject claim ('sub') must be a canonical UUID string",
                            null
                    );
                    return OAuth2TokenValidatorResult.failure(error);
                }
            } catch (IllegalArgumentException ex) {
                OAuth2Error error = new OAuth2Error(
                        OAuth2ErrorCodes.INVALID_TOKEN,
                        "Subject claim ('sub') must be a valid canonical UUID",
                        null
                );
                return OAuth2TokenValidatorResult.failure(error);
            }

            // roles must be Collection of Strings if present
            if (token.hasClaim("roles")) {
                Object roles = token.getClaim("roles");
                if (roles != null) {
                    if (!(roles instanceof Collection<?> col)) {
                        OAuth2Error error = new OAuth2Error(
                                OAuth2ErrorCodes.INVALID_TOKEN,
                                "The 'roles' claim must be an array of strings",
                                null
                        );
                        return OAuth2TokenValidatorResult.failure(error);
                    }
                    for (Object roleItem : col) {
                        if (!(roleItem instanceof String)) {
                            OAuth2Error error = new OAuth2Error(
                                    OAuth2ErrorCodes.INVALID_TOKEN,
                                    "Elements in 'roles' claim array must be strings",
                                    null
                            );
                            return OAuth2TokenValidatorResult.failure(error);
                        }
                    }
                }
            }

            return OAuth2TokenValidatorResult.success();
        };

        return new DelegatingOAuth2TokenValidator<>(
                timestampValidator,
                issuerValidator,
                audienceValidator,
                requiredClaimsValidator
        );
    }
}
