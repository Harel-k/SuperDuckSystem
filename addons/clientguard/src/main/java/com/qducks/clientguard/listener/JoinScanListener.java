package com.qducks.clientguard.listener;

import com.qducks.clientguard.SuperDuckClientGuard;
import com.qducks.clientguard.detect.ClientScanner;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class JoinScanListener implements Listener {
    private final SuperDuckClientGuard plugin;
    private final ClientScanner scanner;

    public JoinScanListener(SuperDuckClientGuard plugin, ClientScanner scanner) {
        this.plugin = plugin;
        this.scanner = scanner;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("scan.on-join", true)) return;
        long delay = Math.max(20L, plugin.getConfig().getLong("scan.join-delay-ticks", 80L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) scanner.scanAll(event.getPlayer());
        }, delay);
    }
}
