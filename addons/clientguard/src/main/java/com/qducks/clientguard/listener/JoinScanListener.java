package com.qducks.clientguard.listener;

import com.qducks.clientguard.SuperDuckClientGuard;
import com.qducks.clientguard.detect.ClientScanner;
import com.qducks.clientguard.policy.PolicyEngine;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class JoinScanListener implements Listener {
    private final SuperDuckClientGuard plugin;
    private final ClientScanner scanner;
    private final PolicyEngine policy;

    public JoinScanListener(SuperDuckClientGuard plugin, ClientScanner scanner, PolicyEngine policy) {
        this.plugin = plugin;
        this.scanner = scanner;
        this.policy = policy;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("scan.on-join", true)) return;
        long delay = Math.max(20L, plugin.getConfig().getLong("scan.join-delay-ticks", 80L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) scanner.scanAll(event.getPlayer());
        }, delay);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        policy.onQuit(event.getPlayer().getUniqueId());
    }
}
