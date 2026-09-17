package com.qducks.superducksystem.rtp;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class RtpListener implements Listener {
    private final RtpService service;

    public RtpListener(RtpService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!service.isPending(event.getPlayer())) return;
        service.handleMove(event.getPlayer(), event.getTo());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.cancel(event.getPlayer(), true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        service.cancel(event.getEntity(), true);
    }
}
