package com.aethercore.backend.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Immutable properties for JWT validation and trust establishment.
 * Fails fast if required public key configuration is absent.
 */
@ConfigurationProperties(prefix = "aethercore.security.jwt")
public class AetherSecurityProperties {

    private String issuer = "https://auth.aethercore.com";
    private String audience = "aethercore";
    private String publicKeyPem;
    private long clockSkewSeconds = 60;

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public String getPublicKeyPem() {
        return publicKeyPem;
    }

    public void setPublicKeyPem(String publicKeyPem) {
        this.publicKeyPem = publicKeyPem;
    }

    public long getClockSkewSeconds() {
        return clockSkewSeconds;
    }

    public void setClockSkewSeconds(long clockSkewSeconds) {
        this.clockSkewSeconds = clockSkewSeconds;
    }

    /**
     * Parses the configured RSA public key PEM string into an RSAPublicKey.
     * Fails startup explicitly if public-key-pem is missing or malformed.
     */
    public RSAPublicKey getParsedPublicKey() {
        if (publicKeyPem == null || publicKeyPem.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Missing required security trust configuration: 'aethercore.security.jwt.public-key-pem'. " +
                    "A valid RSA public key PEM must be supplied via configuration."
            );
        }
        try {
            String sanitizedPem = publicKeyPem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] decoded = Base64.getDecoder().decode(sanitizedPem);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(decoded));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse RSA public key from 'aethercore.security.jwt.public-key-pem'", ex);
        }
    }
}
