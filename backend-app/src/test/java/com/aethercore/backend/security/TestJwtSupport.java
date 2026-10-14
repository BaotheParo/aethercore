package com.aethercore.backend.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Test support utility generating ephemeral RSA 2048-bit key pairs and compact signed JWS tokens.
 * All tokens are signed with real RSA private keys; no mock decoders or with(jwt()) mocks.
 */
public final class TestJwtSupport {

    public static final String DEFAULT_ISSUER = "https://auth.aethercore.com";
    public static final String DEFAULT_AUDIENCE = "aethercore";

    public static final KeyPair TEST_KEY_PAIR;
    public static final KeyPair UNTRUSTED_KEY_PAIR;
    public static final String TEST_PUBLIC_KEY_PEM;

    static {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            TEST_KEY_PAIR = kpg.generateKeyPair();
            UNTRUSTED_KEY_PAIR = kpg.generateKeyPair();
            TEST_PUBLIC_KEY_PEM = toPem(TEST_KEY_PAIR.getPublic());
        } catch (NoSuchAlgorithmException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private TestJwtSupport() {}

    public static String toPem(PublicKey publicKey) {
        String base64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
    }

    public static String createValidTraderToken(UUID callerId) {
        return createToken(callerId, List.of("TRADER"), DEFAULT_ISSUER, DEFAULT_AUDIENCE, Instant.now().plusSeconds(3600), null);
    }

    public static String createToken(UUID callerId, List<String> roles) {
        return createToken(callerId, roles, DEFAULT_ISSUER, DEFAULT_AUDIENCE, Instant.now().plusSeconds(3600), null);
    }

    public static String createToken(
            UUID callerId,
            List<String> roles,
            String issuer,
            String audience,
            Instant exp,
            Instant nbf
    ) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
        if (callerId != null) {
            builder.subject(callerId.toString());
        }
        if (issuer != null) {
            builder.issuer(issuer);
        }
        if (audience != null) {
            builder.audience(audience);
        }
        builder.issueTime(new Date());
        if (exp != null) {
            builder.expirationTime(Date.from(exp));
        }
        if (nbf != null) {
            builder.notBeforeTime(Date.from(nbf));
        }
        if (roles != null) {
            builder.claim("roles", roles);
        }

        return signToken(builder.build(), (RSAPrivateKey) TEST_KEY_PAIR.getPrivate());
    }

    public static String createTokenWithWrongKey(UUID callerId, List<String> roles) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(callerId.toString())
                .issuer(DEFAULT_ISSUER)
                .audience(DEFAULT_AUDIENCE)
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .claim("roles", roles)
                .build();
        return signToken(claims, (RSAPrivateKey) UNTRUSTED_KEY_PAIR.getPrivate());
    }

    public static String createTokenWithWrongIssuer(UUID callerId, List<String> roles, String wrongIssuer) {
        return createToken(callerId, roles, wrongIssuer, DEFAULT_AUDIENCE, Instant.now().plusSeconds(3600), null);
    }

    public static String createTokenWithWrongAudience(UUID callerId, List<String> roles, String wrongAudience) {
        return createToken(callerId, roles, DEFAULT_ISSUER, wrongAudience, Instant.now().plusSeconds(3600), null);
    }

    public static String createTokenWithoutAudience(UUID callerId, List<String> roles) {
        return createToken(callerId, roles, DEFAULT_ISSUER, null, Instant.now().plusSeconds(3600), null);
    }

    public static String createExpiredToken(UUID callerId, List<String> roles) {
        // Expired 2 hours ago (far beyond 60s clock skew)
        return createToken(callerId, roles, DEFAULT_ISSUER, DEFAULT_AUDIENCE, Instant.now().minusSeconds(7200), null);
    }

    public static String createFutureNbfToken(UUID callerId, List<String> roles) {
        // Effective in 2 hours (far beyond 60s clock skew)
        return createToken(callerId, roles, DEFAULT_ISSUER, DEFAULT_AUDIENCE, Instant.now().plusSeconds(14400), Instant.now().plusSeconds(7200));
    }

    public static String createTokenWithoutExp(UUID callerId, List<String> roles) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(callerId.toString())
                .issuer(DEFAULT_ISSUER)
                .audience(DEFAULT_AUDIENCE)
                .issueTime(new Date())
                .claim("roles", roles)
                .build();
        return signToken(claims, (RSAPrivateKey) TEST_KEY_PAIR.getPrivate());
    }

    public static String createTokenWithInvalidSub(String invalidSub, List<String> roles) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(invalidSub)
                .issuer(DEFAULT_ISSUER)
                .audience(DEFAULT_AUDIENCE)
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .claim("roles", roles)
                .build();
        return signToken(claims, (RSAPrivateKey) TEST_KEY_PAIR.getPrivate());
    }

    public static String createTokenWithoutSub(List<String> roles) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(DEFAULT_ISSUER)
                .audience(DEFAULT_AUDIENCE)
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .claim("roles", roles)
                .build();
        return signToken(claims, (RSAPrivateKey) TEST_KEY_PAIR.getPrivate());
    }

    public static String createTokenWithRolesPayload(UUID callerId, Object rolesPayload) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(callerId.toString())
                .issuer(DEFAULT_ISSUER)
                .audience(DEFAULT_AUDIENCE)
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .claim("roles", rolesPayload)
                .build();
        return signToken(claims, (RSAPrivateKey) TEST_KEY_PAIR.getPrivate());
    }

    private static String signToken(JWTClaimsSet claims, RSAPrivateKey privateKey) {
        try {
            JWSSigner signer = new RSASSASigner(privateKey);
            SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            signedJWT.sign(signer);
            return signedJWT.serialize();
        } catch (Exception ex) {
            throw new RuntimeException("Failed to generate test signed JWT", ex);
        }
    }
}
