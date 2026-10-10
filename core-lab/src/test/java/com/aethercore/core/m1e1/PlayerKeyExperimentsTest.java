package com.aethercore.core.m1e1;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * M1-E1 Test Suite:
 * Deterministic laboratory experiments verifying identity vs value equality,
 * equals/hashCode contracts, and HashMap/HashSet behavior across 4 key variants.
 */
public class PlayerKeyExperimentsTest {

    private static final UUID UUID_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID UUID_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String SERVER_A = "us-east-1";
    private static final String SERVER_B = "eu-west-1";

    @Nested
    @DisplayName("Variant A — Identity Behavior (No Override)")
    class VariantATests {

        @Test
        @DisplayName("Same reference yields equality and successful map retrieval")
        void sameReferenceEquality() {
            IdentityPlayerKey key1 = new IdentityPlayerKey(UUID_1, SERVER_A);
            IdentityPlayerKey keyRef = key1;

            assertTrue(key1 == keyRef, "References must be identical");
            assertEquals(key1, keyRef, "equals() on same reference must be true");

            Map<IdentityPlayerKey, String> map = new HashMap<>();
            map.put(key1, "Player Data A");

            assertEquals("Player Data A", map.get(keyRef), "Retrieval with same reference must succeed");
        }

        @Test
        @DisplayName("Distinct instances with identical fields fail equals and map lookup")
        void distinctInstancesFailLookup() {
            IdentityPlayerKey key1 = new IdentityPlayerKey(UUID_1, SERVER_A);
            IdentityPlayerKey key2 = new IdentityPlayerKey(UUID_1, SERVER_A);

            assertFalse(key1 == key2, "Separate object allocations must not be == ");
            assertNotEquals(key1, key2, "Default Object.equals() must return false for distinct instances");

            Map<IdentityPlayerKey, String> map = new HashMap<>();
            map.put(key1, "Player Data A");

            // Key lookup failure because key1.equals(key2) is false
            assertNull(map.get(key2), "Lookup with distinct instance must return null in Variant A");

            // HashSet deduplication failure
            Set<IdentityPlayerKey> set = new HashSet<>();
            set.add(key1);
            set.add(key2);
            assertEquals(2, set.size(), "HashSet fails to deduplicate identical data when equals() is not overridden");
        }
    }

    @Nested
    @DisplayName("Variant B — Equals Only (Broken Contract)")
    class VariantBTests {

        @Test
        @DisplayName("equals() returns true on identical fields but hashCodes may diverge")
        void equalsReturnsTrue() {
            EqualsOnlyPlayerKey key1 = new EqualsOnlyPlayerKey(UUID_1, SERVER_A);
            EqualsOnlyPlayerKey key2 = new EqualsOnlyPlayerKey(UUID_1, SERVER_A);

            assertEquals(key1, key2, "equals() must evaluate to true on equal fields");

            // Check if identity hash codes differ
            int h1 = key1.hashCode();
            int h2 = key2.hashCode();

            Map<EqualsOnlyPlayerKey, String> map = new HashMap<>();
            map.put(key1, "Player Data B");

            if (h1 != h2) {
                // When hash codes differ, HashMap lookup almost always fails due to bucket mismatch
                // Even though key1.equals(key2) is true!
                // This proves: equals() alone is insufficient for Map/Set lookups!
                assertNull(map.get(key2), "Lookup with key2 fails when hash codes differ despite equals() == true");
            } else {
                // In rare identity hash collisions, the lookup could pass, which is INCONCLUSIVE on contract validity
                assertEquals("Player Data B", map.get(key2));
            }
        }
    }

    @Nested
    @DisplayName("Variant C — Immutable Value Key (Proper Contract)")
    class VariantCTests {

        @Test
        @DisplayName("Validates equivalence relation (reflexive, symmetric, transitive, null-safe, type-safe)")
        void validatesEquivalenceRelation() {
            ValuePlayerKey x = new ValuePlayerKey(UUID_1, SERVER_A);
            ValuePlayerKey y = new ValuePlayerKey(UUID_1, SERVER_A);
            ValuePlayerKey z = new ValuePlayerKey(UUID_1, SERVER_A);

            // Reflexive
            assertEquals(x, x);

            // Symmetric
            assertEquals(x.equals(y), y.equals(x));
            assertTrue(x.equals(y));

            // Transitive
            assertTrue(x.equals(y));
            assertTrue(y.equals(z));
            assertEquals(x, z);

            // Null-safe & Type-safe
            assertFalse(x.equals(null));
            assertFalse(x.equals("A String Object"));

            // Unequal fields
            ValuePlayerKey diffUuid = new ValuePlayerKey(UUID_2, SERVER_A);
            ValuePlayerKey diffServer = new ValuePlayerKey(UUID_1, SERVER_B);
            assertNotEquals(x, diffUuid);
            assertNotEquals(x, diffServer);
        }

        @Test
        @DisplayName("Guarantees hashCode consistency: x.equals(y) => x.hashCode() == y.hashCode()")
        void guaranteesHashCodeConsistency() {
            ValuePlayerKey key1 = new ValuePlayerKey(UUID_1, SERVER_A);
            ValuePlayerKey key2 = new ValuePlayerKey(UUID_1, SERVER_A);

            assertEquals(key1, key2);
            assertEquals(key1.hashCode(), key2.hashCode(), "Equal objects must have identical hash codes");
        }

        @Test
        @DisplayName("HashMap and HashSet behave deterministically with ValuePlayerKey")
        void deterministicCollectionBehavior() {
            ValuePlayerKey key1 = new ValuePlayerKey(UUID_1, SERVER_A);
            ValuePlayerKey key2 = new ValuePlayerKey(UUID_1, SERVER_A);

            Map<ValuePlayerKey, String> map = new HashMap<>();
            map.put(key1, "Alpha Record");

            // Successful lookup by equivalent key
            assertEquals("Alpha Record", map.get(key2));

            // Overwrite maintains size == 1
            map.put(key2, "Updated Record");
            assertEquals(1, map.size());
            assertEquals("Updated Record", map.get(key1));

            // HashSet deduplication
            Set<ValuePlayerKey> set = new HashSet<>();
            set.add(key1);
            set.add(key2);
            assertEquals(1, set.size(), "HashSet must correctly deduplicate equivalent value keys");
        }
    }

    @Nested
    @DisplayName("Variant D — Mutable Key (Corruption Demo)")
    class VariantDTests {

        @Test
        @DisplayName("Mutating key after insertion corrupts hash lookup and traps ghost entry")
        void mutatingKeyCorruptsLookup() {
            MutablePlayerKey key = new MutablePlayerKey(UUID_1, SERVER_A);
            int initialHash = key.hashCode();

            Map<MutablePlayerKey, String> map = new HashMap<>();
            map.put(key, "Active Player Session");

            assertEquals(1, map.size());
            assertEquals("Active Player Session", map.get(key));

            // Mutate the field that participates in equals/hashCode
            key.setServerId(SERVER_B);
            int mutatedHash = key.hashCode();

            // Verify the hash code actually changed
            assertNotEquals(initialHash, mutatedHash, "Modifying serverId must alter hash code");

            // Lookup using the mutated key reference fails because HashMap calculates bucket using mutatedHash,
            // while the Entry is located in the bucket computed from initialHash!
            assertNull(map.get(key), "Lookup with mutated key fails even on the exact same object reference!");

            // Lookup with new key having old value fails because equals() compares against mutated key!
            MutablePlayerKey oldValKey = new MutablePlayerKey(UUID_1, SERVER_A);
            assertNull(map.get(oldValKey), "Lookup with old value key fails on equals() check");

            // Crucial observation: Map size is STILL 1 (the entry is not deleted, it is just lost / unreachable)
            assertEquals(1, map.size(), "Map still holds 1 entry, creating a memory leak / ghost entry");
            assertTrue(map.containsValue("Active Player Session"), "Value is still trapped in the map table");

            // Direct iteration proves entry is physically present in the table iteration even though get() fails
            boolean foundViaIteration = false;
            for (Map.Entry<MutablePlayerKey, String> entry : map.entrySet()) {
                if ("Active Player Session".equals(entry.getValue())) {
                    foundViaIteration = true;
                    assertSame(key, entry.getKey());
                }
            }
            assertTrue(foundViaIteration, "Entry is physically present and observable during full map entry iteration");
        }

        @Test
        @DisplayName("Correct handling: Remove before mutating and reinsert")
        void safeMutableWorkflow() {
            MutablePlayerKey key = new MutablePlayerKey(UUID_1, SERVER_A);
            Map<MutablePlayerKey, String> map = new HashMap<>();
            map.put(key, "Active Player Session");

            // Safe workflow:
            // Step 1: Remove entry using original state
            String session = map.remove(key);
            assertNotNull(session);
            assertEquals(0, map.size());

            // Step 2: Mutate key
            key.setServerId(SERVER_B);

            // Step 3: Re-insert into map
            map.put(key, session);
            assertEquals(1, map.size());
            assertEquals("Active Player Session", map.get(key));
        }
    }
}
