package com.aethercore.backend.market.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequestFingerprintTest {

    @Test
    @DisplayName("Fingerprint is deterministic and produces 64-char lowercase hex")
    void deterministicAndFormat() {
        String f1 = RequestFingerprint.compute("SWORD_OF_LIGHT", 5);
        String f2 = RequestFingerprint.compute("SWORD_OF_LIGHT", 5);

        assertNotNull(f1);
        assertEquals(64, f1.length());
        assertEquals(f1.toLowerCase(), f1);
        assertEquals(f1, f2, "Fingerprint must be deterministic for identical input");
    }

    @Test
    @DisplayName("Different SKU or quantity produces different fingerprint")
    void differentInputsProduceDifferentFingerprints() {
        String base = RequestFingerprint.compute("SWORD_OF_LIGHT", 5);
        String diffSku = RequestFingerprint.compute("SHIELD_OF_LIGHT", 5);
        String diffQty = RequestFingerprint.compute("SWORD_OF_LIGHT", 10);

        assertNotEquals(base, diffSku);
        assertNotEquals(base, diffQty);
        assertNotEquals(diffSku, diffQty);
    }

    @Test
    @DisplayName("Validation: null SKU is rejected")
    void nullSkuRejected() {
        assertThrows(NullPointerException.class, () -> RequestFingerprint.compute(null, 1));
    }
}