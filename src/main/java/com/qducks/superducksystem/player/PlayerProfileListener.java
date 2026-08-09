package com.qducks.superducksystem.player;

import com.qducks.superducksystem.SuperDuckSystem;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

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
    }
}
