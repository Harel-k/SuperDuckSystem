package com.qducks.clientguard.sanction;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SanctionLock {
    private final Map<UUID, Boolean> previousInvulnerable = new ConcurrentHashMap<>();

    public void lock(Player player) {
        previousInvulnerable.putIfAbsent(player.getUniqueId(), player.isInvulnerable());
        player.setInvulnerable(true);
    }

    public void unlock(Player player) {
        Boolean previous = previousInvulnerable.remove(player.getUniqueId());
        if (previous != null && player.isOnline()) {
            player.setInvulnerable(previous);
        }
    }

    public boolean isLocked(UUID uuid) {
        return previousInvulnerable.containsKey(uuid);
    }

    public void forget(UUID uuid) {
        previousInvulnerable.remove(uuid);
    }
}
