package com.aethercore.core.m1e1;

import java.util.Objects;
import java.util.UUID;

/**
 * Variant C (Immutable Value Key - Proper Contract):
 * Final class with immutable fields, symmetric equals() and consistent hashCode() implementation.
 * Satisfies all contracts:
 * 1. Reflexive: x.equals(x) == true
 * 2. Symmetric: x.equals(y) == y.equals(x)
 * 3. Transitive: x.equals(y) && y.equals(z) => x.equals(z)
 * 4. Consistent: multiple invocations return same result
 * 5. Null-safe: x.equals(null) == false
 * 6. HashCode Consistency: x.equals(y) => x.hashCode() == y.hashCode()
 */
public final class ValuePlayerKey {
    private final UUID playerId;
    private final String serverId;

    public ValuePlayerKey(UUID playerId, String serverId) {
        if (playerId == null || serverId == null) {
            throw new IllegalArgumentException("playerId and serverId must not be null");
        }
        this.playerId = playerId;
        this.serverId = serverId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getServerId() {
        return serverId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ValuePlayerKey that = (ValuePlayerKey) o;
        return Objects.equals(playerId, that.playerId) &&
               Objects.equals(serverId, that.serverId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerId, serverId);
    }

    @Override
    public String toString() {
        return "ValuePlayerKey{playerId=" + playerId + ", serverId='" + serverId + "'}";
    }
}
