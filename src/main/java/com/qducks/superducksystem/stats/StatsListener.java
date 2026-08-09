package com.qducks.superducksystem.stats;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class StatsListener implements Listener {
    private final StatsService service;

    public StatsListener(StatsService service) {
        this.service = service;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.onJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.onQuit(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        service.incrementDeath(dead.getUniqueId());
        Player killer = dead.getKiller();
        if (killer != null && !killer.getUniqueId().equals(dead.getUniqueId())) {
            service.incrementKill(killer.getUniqueId());
        }
    }
}
