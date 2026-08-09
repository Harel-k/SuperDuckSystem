package com.qducks.superducksystem.player;

import com.qducks.superducksystem.SuperDuckSystem;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerProfileListener implements Listener {
    private final SuperDuckSystem plugin;

    public PlayerProfileListener(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.database().upsertPlayer(
                event.getPlayer().getUniqueId(),
                event.getPlayer().getName(),
                System.currentTimeMillis()
        );
        plugin.economy().warm(event.getPlayer().getUniqueId()).exceptionally(error -> {
            plugin.getLogger().warning("Could not warm economy profile for " + event.getPlayer().getName() + ": " + error.getMessage());
            return null;
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.economy().unload(event.getPlayer().getUniqueId());
    }
}
