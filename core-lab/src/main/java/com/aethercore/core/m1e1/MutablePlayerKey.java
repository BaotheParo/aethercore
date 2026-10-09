package com.aethercore.core.m1e1;

import java.util.Objects;
import java.util.UUID;

/**
 * Variant D (Mutable Key - Dangerous Hash Corruption):
 * Key with mutable serverId field that participates in equals() and hashCode().
 * Modifying serverId after inserting into a hash-based collection alters the computed hash code
 * and invalidates the internal bucket location, causing map lookup failures and ghost entries.
 *
 * FIXTURE NOTE: Experimental fixture for demonstrating mutable key hazards.
 */
public class MutablePlayerKey {
    private final UUID playerId;
    private String serverId; // Mutable field

    public MutablePlayerKey(UUID playerId, String serverId) {
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

    public void setServerId(String serverId) {
        if (serverId == null) {
            throw new IllegalArgumentException("serverId must not be null");
        }
        this.serverId = serverId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MutablePlayerKey that = (MutablePlayerKey) o;
        return Objects.equals(playerId, that.playerId) &&
               Objects.equals(serverId, that.serverId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerId, serverId);
    }

    @Override
    public String toString() {
        return "MutablePlayerKey{playerId=" + playerId + ", serverId='" + serverId + "'}";
    }
}
