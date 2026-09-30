package com.qducks.clientguard.sanction;

import com.qducks.clientguard.SuperDuckClientGuard;
import org.bukkit.entity.Player;

public final class HomeStateAdapter {
    private final SuperDuckClientGuard plugin;

    public HomeStateAdapter(SuperDuckClientGuard plugin) {
        this.plugin = plugin;
    }

    /**
     * Homes are external state. Destructive enforcement must stay blocked until
     * we have an adapter for the actual homes provider that can both snapshot
     * and wipe the player's homes safely.
     */
    public boolean ready() {
        return !plugin.getConfig().getBoolean("external.homes.required", true);
    }

    public boolean snapshotAndWipe(Player player) {
        return !plugin.getConfig().getBoolean("external.homes.required", true);
    }

    public String blocker() {
        return ready() ? "" : "Homes integration is required but no snapshot+wipe adapter is configured yet";
    }
}
