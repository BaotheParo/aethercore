package com.aethercore.core;

import java.util.UUID;

/**
 * M1-E1 Baseline Starting Point:
 * PlayerKey represents an entity identifier composed of a player's UUID and a server ID.
 * NOTE (Day 1 Baseline): equals() and hashCode() are NOT yet overridden.
 */
public class PlayerKey {
    private final UUID playerId;
    private final String serverId;

    public PlayerKey(UUID playerId, String serverId) {
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
        return "PlayerKey{" +
                "playerId=" + playerId +
                ", serverId='" + serverId + ''' +
                '}';
    }
}
