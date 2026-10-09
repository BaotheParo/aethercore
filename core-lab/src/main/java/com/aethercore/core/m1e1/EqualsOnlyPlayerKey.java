package com.aethercore.core.m1e1;

import java.util.Objects;
import java.util.UUID;

/**
 * Variant B (Equals Only - Broken Contract):
 * Overrides equals() comparing fields, but INTENTIONALLY omits hashCode() override.
 * Violates the general contract of Object.hashCode():
 * "If two objects are equal according to the equals(Object) method, then calling the
 * hashCode method on each of the two objects must produce the same integer result."
 *
 * FIXTURE NOTE: Experimental fixture for illustrating hash bucket misplacement.
 */
public class EqualsOnlyPlayerKey {
    private final UUID playerId;
    private final String serverId;

    public EqualsOnlyPlayerKey(UUID playerId, String serverId) {
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
        EqualsOnlyPlayerKey that = (EqualsOnlyPlayerKey) o;
        return Objects.equals(playerId, that.playerId) &&
               Objects.equals(serverId, that.serverId);
    }

    // hashCode() is intentionally NOT overridden (inherits System identity hashCode)

    @Override
    public String toString() {
        return "EqualsOnlyPlayerKey{playerId=" + playerId + ", serverId='" + serverId + "'}";
    }
}
