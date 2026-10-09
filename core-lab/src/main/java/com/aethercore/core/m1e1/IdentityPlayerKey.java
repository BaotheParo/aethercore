package com.aethercore.core.m1e1;

import java.util.Objects;
import java.util.UUID;

/**
 * Variant A (Baseline / Identity Equality):
 * Does NOT override equals() and hashCode().
 * Equality and hash code are strictly determined by object identity (Object.class implementation).
 *
 * FIXTURE NOTE: This class is intentionally created as an experimental fixture to demonstrate
 * the consequences of missing equals/hashCode contract.
 */
public class IdentityPlayerKey {
    private final UUID playerId;
    private final String serverId;

    public IdentityPlayerKey(UUID playerId, String serverId) {
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
    public String toString() {
        return "IdentityPlayerKey{playerId=" + playerId + ", serverId='" + serverId + "'}";
    }
}
