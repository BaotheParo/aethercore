package com.aethercore.backend.market.idempotency;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Deterministic, canonical request fingerprint generator using JDK standard libraries.
 *
 * <p>Encoding contract (Version 1, Big-Endian):
 * <pre>
 *   writeByte(1)
 *   writeInt(sku.getBytes(UTF_8).length)
 *   write(sku.getBytes(UTF_8))
 *   writeInt(quantity)
 * </pre>
 * Followed by SHA-256 digested to 64 lowercase hexadecimal characters.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {}

    public static String compute(String sku, int quantity) {
        Objects.requireNonNull(sku, "sku must not be null");
        byte[] skuBytes = sku.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeByte(1); // Version 1
            dos.writeInt(skuBytes.length);
            dos.write(skuBytes);
            dos.writeInt(quantity);
            dos.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize canonical fingerprint data", e);
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(baos.toByteArray());
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available in current JVM", e);
        }
    }
}